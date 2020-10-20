package com.evernym.verity.actor.agent

import akka.actor.ActorRef
import akka.pattern.ask
import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject}
import com.evernym.verity.actor.agent.agency.{AgencyInfo, GetAgencyIdentity}
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.relationship.RelUtilParam
import com.evernym.verity.actor.agent.state._
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.cache._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.logging.LoggingUtil.getAgentIdentityLoggerByClass
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.HasAgentWallet
import com.evernym.verity.util.Util._
import com.evernym.verity.vault._
import com.evernym.verity.Exceptions
import com.evernym.verity.metrics.CustomMetrics.AS_ACTOR_AGENT_STATE_SIZE
import com.evernym.verity.metrics.MetricsWriter
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * common interface for any type of agent actor (agencyAgent, agencyPairwiseAgent, userAgent, userPairwiseAgent)
 */
trait AgentCommon
  extends AgentStateUpdateInterface
    with AgentIdentity
    with HasAgentWallet
    with HasSetRoute
    with ResourceUsageCommon { this: AgentPersistentActor =>

  type StateType <: AgentStateInterface
  def state: StateType

  override def postSuccessfulActorRecovery(): Unit = {
    Option(state).foreach { s =>
      val stateSize = s.serializedSize
      if (stateSize >= 0) { // so only states that can calculate size are part the metric
        MetricsWriter.histogramApi.recordWithTag(
          AS_ACTOR_AGENT_STATE_SIZE,
          stateSize,
          "actor_class" -> this.getClass.getSimpleName,
        )
      }
    }
  }

  lazy val logger: Logger = getAgentIdentityLoggerByClass(this, getClass)

  def agentActorContext: AgentActorContext
  def agentWalletSeed: Option[String] = state.agentWalletSeed
  def agentMsgTransformer: AgentMsgTransformer = agentActorContext.agentMsgTransformer

  def agencyDIDReq: DID = state.agencyDID.getOrElse(
    throw new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode, Option("agent not yet created")))

  def ownerDID: Option[DID]
  def ownerDIDReq: DID = ownerDID.getOrElse(throw new RuntimeException("owner DID not found"))
  def ownerAgentKeyDID: Option[DID]

  def domainId: DomainId = ownerDIDReq    //TODO: can be related with 'ownerDIDReq'

  lazy val walletVerKeyCacheHelper = new WalletVerKeyCacheHelper(wap, walletDetail.walletAPI, appConfig)
  def getVerKeyReqViaCache(did: DID, getFromPool: Boolean = false): VerKey = walletVerKeyCacheHelper.getVerKeyReqViaCache(did, getFromPool)

  lazy val cacheFetchers: Map[Int, CacheValueFetcher] = Map (
    AGENT_ACTOR_CONFIG_CACHE_FETCHER_ID -> new AgentConfigCacheFetcher(agentActorContext.agentMsgRouter, agentActorContext.appConfig)
  )

  /**
   * per agent actor cache
   */
  lazy val agentCache = new Cache("AC", cacheFetchers)

  /**
   * general/global (per actor system) cache
   */
  lazy val generalCache: Cache = agentActorContext.generalCache

  lazy val singletonParentProxyActor: ActorRef =
    getActorRefFromSelection(SINGLETON_PARENT_PROXY, agentActorContext.system)(agentActorContext.appConfig)

  def setAndOpenWalletIfExists(actorEntityId: String): Unit = {
    try {
      setWalletSeed(actorEntityId)
      openWalletIfExists(wap)
      logger.debug(s"wallet successfully initialized and opened for actorEntityId: $actorEntityId")
    } catch {
      case e: Exception =>
        logger.error(s"wallet failed to initialize, create, and open for actorEntityId: $actorEntityId")
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        throw e
    }
  }

  def setWalletSeed(actorEntityId: String): Unit = {
    if (agentWalletSeed.nonEmpty && ! agentWalletSeed.contains(actorEntityId))
      throw new InternalServerErrorException(ALREADY_EXISTS.statusCode, Option("agent wallet seed already set to different value"))
    setAgentWalletSeed(actorEntityId)
  }

  def openWalletIfExists(wap: WalletAccessParam): Boolean = {
    try {
      agentActorContext.walletAPI.openWallet(wap)
      true
    } catch {
      case _: WalletAlreadyOpened =>
        logger.debug(s"wallet ${wap.walletName} is already open")
        true
      case _: WalletDoesNotExist | _: WalletInvalidState =>
        //nothing to do if wallet is not yet created
        false
    }
  }

  def setAgentActorDetail(forDID: DID): Future[Any] = {
    agentActorContext.agentMsgRouter.execute(GetRoute(forDID)) flatMap {
      case Some(aa: ActorAddressDetail) => self ? SetAgentActorDetail(forDID, aa.address)
      case None => Future.successful("agent not yet created")
    }
  }

  def getAgencyVerKeyFut: Future[VerKey] = {
    val gad = GetAgencyIdentity(agencyDIDReq, getEndpoint = false)
    val gadFutResp = agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(agencyDIDReq, gad))
    gadFutResp.map {
      case ai: AgencyInfo if ! ai.isErrorInFetchingVerKey => ai.verKeyReq
      case _ => throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("agency ver key not found"))
    }
  }

  /**
   * handler to set correct agent routes (in case it is not set)
   */
  def updateRoute(): Unit = {
    val sndr = sender()
    state.myDid.map(did => setRoute(did))
      .getOrElse(Future.successful("self rel not yet setup"))
      .map { r =>
        sndr ! r
      }
  }

  /**
   * relationship util param, used in 'postSuccessfulActorRecovery' function call
   * to convert any LegacyAuthorizedKey to AuthorizedKey (by fetching ver key from the wallet)
   * @return
   */
  implicit def relationshipUtilParam: RelUtilParam = {
    if (isSuccessfullyRecovered) {
      //if actor is successfully recovered (meaning all events are applied) then,
      // a 'walletVerKeyCacheHelper' is provided in 'RelUtilParam',
      // which then further used to extract ver key from the wallet and prepare AuthorizedKey
      RelUtilParam(appConfig, state.thisAgentKeyDID, Option(walletVerKeyCacheHelper))
    } else {
      //if actor is NOT yet recovered, then, 'walletVerKeyCacheHelper' is not provided in 'RelUtilParam',
      // in which case a 'LegacyAuthorizedKey' gets created and then as part of 'postSuccessfulActorRecovery'
      // it gets converted to 'AuthorizedKey'
      RelUtilParam(appConfig, state.thisAgentKeyDID, None)
    }
  }
}

/**
 * This is information about "self rel" agent actor (either AgencyAgent or UserAgent)
 *
 * @param did domain DID
 * @param actorEntityId entity id of the actor which belongs to the self relationship actor
 */
case class SetAgentActorDetail(did: DID, actorEntityId: String) extends ActorMessageClass
case class AgentActorDetailSet(did: DID, actorEntityId: String) extends ActorMessageClass

case class SetAgencyIdentity(did: DID) extends ActorMessageClass
case class AgencyIdentitySet(did: DID) extends ActorMessageClass
case object UpdateRoute extends ActorMessageObject