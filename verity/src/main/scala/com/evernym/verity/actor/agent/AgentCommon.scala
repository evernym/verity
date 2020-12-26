package com.evernym.verity.actor.agent

import java.time.ZonedDateTime

import akka.actor.ActorRef
import akka.pattern.ask
import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.agency.{AgencyAgent, AgencyInfo, GetAgencyIdentity}
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.relationship.RelUtilParam
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
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.agent.state.base.{AgentStateInterface, AgentStateUpdateInterface}
import com.evernym.verity.actor.resourceusagethrottling.EntityId
import com.evernym.verity.metrics.CustomMetrics.AS_ACTOR_AGENT_STATE_SIZE
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.actor.ProtocolIdDetail
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.google.protobuf.ByteString
import com.typesafe.scalalogging.Logger
import kamon.metric.MeasurementUnit

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

  var isThreadContextMigrationFinished: Boolean = false

  override def postSuccessfulActorRecovery(): Unit = {
    Option(state).foreach { s =>
      isThreadContextMigrationFinished = state.currentThreadContextSize == 0
      try {
        val stateSize = s.serializedSize
        if (stateSize >= 0) { // so only states that can calculate size are part the metric
          MetricsWriter.histogramApi.recordWithTag(
            AS_ACTOR_AGENT_STATE_SIZE,
            MeasurementUnit.information.bytes,
            stateSize,
            "actor_class" -> this.getClass.getSimpleName,
          )
        }
      } catch {
        case e: RuntimeException =>
          logger.error(s"[$persistenceId] error occurred while calculating state size => " +
            s"state: $s, error: ${e.getMessage}, exception stack trace: " +
            s"${Exceptions.getStackTraceAsSingleLineString(e)}")
      }
    }
  }

  override lazy val logger: Logger = getAgentIdentityLoggerByClass(this, getClass)

  def agentActorContext: AgentActorContext
  def agentWalletId: Option[String] = state.agentWalletId
  def agentMsgTransformer: AgentMsgTransformer = agentActorContext.agentMsgTransformer
  def walletAPI: WalletAPI = agentActorContext.walletAPI

  def agencyDIDReq: DID = state.agencyDID.getOrElse(
    throw new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode, Option("agent not yet created")))

  def ownerDID: Option[DID]
  def ownerDIDReq: DID = ownerDID.getOrElse(throw new RuntimeException("owner DID not found"))
  def ownerAgentKeyDID: Option[DID]
  def domainId: DomainId = ownerDIDReq    //TODO: can be related with 'ownerDIDReq'

  lazy val walletVerKeyCacheHelper = new WalletVerKeyCacheHelper(wap, agentWalletAPI.walletAPI, appConfig)
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
      updateAgentWalletId(actorEntityId)
      logger.debug(s"wallet successfully initialized and opened for actorEntityId: $actorEntityId")
    } catch {
      case e: Exception =>
        logger.error(s"wallet failed to initialize, create, and open for actorEntityId: $actorEntityId")
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        throw e
    }
  }

  def updateAgentWalletId(actorEntityId: String): Unit = {
    if (agentWalletId.nonEmpty && ! agentWalletId.contains(actorEntityId))
      throw new InternalServerErrorException(ALREADY_EXISTS.statusCode, Option("agent wallet id already set to different value"))
    setAgentWalletId(actorEntityId)
  }

  def setAgentActorDetail(forDID: DID): Future[Any] = {
    agentActorContext.agentMsgRouter.execute(GetRoute(forDID)) flatMap {
      case Some(aa: ActorAddressDetail) => self ? SetAgentActorDetail(forDID, aa.address)
      case None => Future.successful("agent not yet created")
    }
  }

  def agencyVerKeyFut(): Future[VerKey] =
    AgencyAgent
      .agencyAgentDetail
      .map(aad => Future.successful(aad.verKey))
      .getOrElse(getAgencyVerKeyFut)

  private def getAgencyVerKeyFut: Future[VerKey] = {
    val gad = GetAgencyIdentity(agencyDIDReq, getEndpoint = false)
    val gadFutResp = agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(agencyDIDReq, gad))
    gadFutResp.map {
      case ai: AgencyInfo if ! ai.isErrorInFetchingVerKey => ai.verKeyReq
      case _ => throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("agency ver key not found"))
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

trait SponsorRelCompanion {
  def apply(sponsorId: Option[String], sponseeId: Option[String]): SponsorRel =
    new SponsorRel(sponsorId.getOrElse(""), sponseeId.getOrElse(""))

  def empty: SponsorRel = new SponsorRel("", "")
}

/**
 *
 * @param newAgentKeyDID DID belonging to the new agent ver key
 * @param forDID pairwise DID for which new pairwise actor needs to be setup
 * @param mySelfRelDID my self relationship DID
 * @param ownerAgentKeyDID DID belonging to owner's agent's ver key
 * @param ownerAgentActorEntityId entity id of owner's agent actor
 * @param pid
 */
case class SetupCreateKeyEndpoint(newAgentKeyDID: DID,
                                  forDID: DID,
                                  mySelfRelDID: DID,
                                  ownerAgentKeyDID: Option[DID] = None,
                                  ownerAgentActorEntityId: Option[EntityId]=None,
                                  pid: Option[ProtocolIdDetail]=None) extends ActorMessageClass

trait SetupEndpoint extends ActorMessageClass {
  def ownerDID: DID
  def agentKeyDID: DID
}

case class SetupAgentEndpoint(ownerDID: DID,
                              agentKeyDID: DID) extends SetupEndpoint

case class SetupAgentEndpoint_V_0_7 (threadId: ThreadId,
                                     ownerDID: DID,
                                     agentKeyDID: DID,
                                     requesterVerKey: VerKey,
                                     sponsorRel: Option[SponsorRel]=None) extends SetupEndpoint

import com.evernym.verity.util.TimeZoneUtil._

trait AgentMsgBase {
  def `type`: String
  def creationTimeInMillis: Long
  def lastUpdatedTimeInMillis: Long
  def getType: String = `type`

  def creationDateTime: ZonedDateTime = getZonedDateTimeFromMillis(creationTimeInMillis)(UTCZoneId)
  def lastUpdatedDateTime: ZonedDateTime = getZonedDateTimeFromMillis(lastUpdatedTimeInMillis)(UTCZoneId)
}


trait ThreadBase {
  def sender_order: Option[Int]
  def received_orders: Map[String, Int]

  def senderOrder: Option[Int] = sender_order
  def senderOrderReq: Int = sender_order.getOrElse(0)
  def receivedOrders: Option[Map[String, Int]] = Option(received_orders)
}

trait MsgDeliveryStatusBase {
  def lastUpdatedTimeInMillis: Long
  def lastUpdatedDateTime: ZonedDateTime = getZonedDateTimeFromMillis(lastUpdatedTimeInMillis)(UTCZoneId)
}

trait PayloadWrapperCompanion {
  def apply(msg: Array[Byte], metadata: Option[PayloadMetadata]): PayloadWrapper = {
    PayloadWrapper(ByteString.copyFrom(msg), metadata)
  }
}

trait PayloadWrapperBase {
  def msg: Array[Byte] = msgBytes.toByteArray
  def msgBytes: ByteString
  def metadata: Option[PayloadMetadata]
  def msgPackFormat: Option[MsgPackFormat] = metadata.map(md => md.msgPackFormat)
}

/**
 * this is only used when this agent has packed a message and it knows its metadata
 * (message type string, message pack version etc)
 * this should NOT be used when this agent is acting as a proxy and just storing a received packed message
 * (as in that case, it may/won't have idea about how that message is packed)
 */
trait PayloadMetadataCompanion {
  def apply(msgTypeStr: String, msgPackFormat: MsgPackFormat): PayloadMetadata = {
    PayloadMetadata(msgTypeStr, msgPackFormat.toString)
  }
  def apply(msgType: MsgType, msgPackFormat: MsgPackFormat): PayloadMetadata = {
    PayloadMetadata(MsgFamily.typeStrFromMsgType(msgType), msgPackFormat.toString)
  }
}

trait PayloadMetadataBase {
  def msgPackFormatStr: String
  def msgPackFormat: MsgPackFormat = MsgPackFormat.fromString(msgPackFormatStr)
}