package com.evernym.verity.actor.agent.agency

import akka.actor.ActorRef
import akka.event.LoggingReceive
import akka.pattern.ask
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, ForbiddenErrorException}
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.relationship.Tags.EDGE_AGENT_KEY
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.relationship.{AnywiseRelationship, DidDocBuilder, Relationship}
import com.evernym.verity.actor.agent.state.base.{AgentStateImplBase, AgentStateUpdateInterface}
import com.evernym.verity.actor.cluster_singleton.{AddMapping, ForKeyValueMapper}
import com.evernym.verity.actor.wallet.{CreateNewKey, CreateWallet, NewKeyCreated, WalletCreated}
import com.evernym.verity.agentmsg.msgpacker.UnpackParam
import com.evernym.verity.cache.{LEDGER_GET_ENDPOINT_CACHE_FETCHER, LEDGER_GET_VER_KEY_CACHE_FETCHER}
import com.evernym.verity.cache.base.{GetCachedObjectParam, KeyDetail}
import com.evernym.verity.cache.fetchers.{GetEndpointParam, GetVerKeyParam}
import com.evernym.verity.config.ConfigConstants
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.ledger.Submitter
import com.evernym.verity.metrics.InternalSpan
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.PackedMsgWrapper
import com.evernym.verity.util.Util._
import com.evernym.verity.vault.KeyParam
import com.evernym.verity.util2.{Exceptions, UrlParam}

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.Left


/**
 The actor that represents the agency as an institution.
 Agency services/features are exposed through this agent.
 There is one of these actors per actor system. Contrast AgencyAgentPairwise.
 */
class AgencyAgent(val agentActorContext: AgentActorContext,
                  generalExecutionContext: ExecutionContext,
                  walletExecutionContext: ExecutionContext)
  extends AgencyAgentCommon
    with AgencyAgentStateUpdateImpl
    with AgencyPackedMsgHandler
    with AgentSnapshotter[AgencyAgentState] {

  private implicit val executionContext: ExecutionContext = generalExecutionContext
  override def futureExecutionContext: ExecutionContext = generalExecutionContext
  override def futureWalletExecutionContext: ExecutionContext = walletExecutionContext

  type StateType = AgencyAgentState
  var state = new AgencyAgentState

  override final def receiveAgentCmd: Receive = commonCmdReceiver orElse cmdReceiver

  val cmdReceiver: Receive = LoggingReceive.withLabel("cmdReceiver") {
    case gad: GetAgencyIdentity                 => sendAgencyIdentity(gad)
    case GetAgencyAgentDetail                   => sendAgencyAgentDetail()
    case glai: GetLocalAgencyIdentity           => sendLocalAgencyIdentity(glai.withDetail)
    case ck: CreateKey                          => createKey(ck)
    case fck: FinishCreateKey                   => finishCreateKey(fck)
    case SetEndpoint                            => setEndpoint()
    case UpdateEndpoint                         => updateEndpoint()
    case pmw: PackedMsgWrapper                  => handlePackedMsg(pmw)
  }

  override final def receiveAgentEvent: Receive = eventReceiver

  val eventReceiver: Receive = {
    //dhh What type of "key" are we talking about here, that gets created?
    case kg: KeyCreated             => handleKeyCreated(kg)
    case _: EndpointSet             => state = state.withIsEndpointSet(true)
    case _: AgentDetailSet          => //nothing to do, kept it for backward compatibility
  }

  def handleKeyCreated(kg: KeyCreated): Unit = {
    state = state
      .withAgencyDIDPair(DidPair(kg.forDID, kg.forDIDVerKey))
      .withThisAgentKeyId(kg.forDID)
    val myDidDoc =
      DidDocBuilder(futureWalletExecutionContext)
        .withDid(kg.forDID)
        .withAuthKey(kg.forDID, kg.forDIDVerKey, Set(EDGE_AGENT_KEY))
        .didDoc
    state = state.withRelationship(AnywiseRelationship(myDidDoc))
  }

  def sendAgencyAgentDetail(): Unit = {
    agencyAgentDetail() match {
      case Some(aad)  => sender ! aad
      case None       => throw new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode)
    }
  }

  def agencyAgentDetail(): Option[AgencyAgentDetail] = {
    state.agencyDIDPair map { adp =>
      AgencyAgentDetail(adp.DID, adp.verKey, entityId)
    }
  }

  def updateAgencyEndpointInLedger(url: UrlParam): Unit = {
    val sndr = sender()
    // This is sort of an example of the problem with futures that Jason wrote about.
    // However, it's not very troubling, because the closure just sends a response;
    // it doesn't modify the actor's state. However, in protocols, when this type of
    // pattern occurs, the state might be modified, and that would be bad.
    val addUrlFutResp = agentActorContext.ledgerSvc.addAttrib(ledgerReqSubmitter, state.myDid_!,
      agentActorContext.ledgerSvc.URL, url.toString)
    addUrlFutResp.map { tr =>
      logger.debug("url added", (LOG_KEY_SRC_DID, state.myDid_!), ("transaction", tr))
      sndr ! EndpointSet(url.toString)
    }.recover {
      case StatusDetailException(statusDetail) =>
        logger.error("could not add url", (LOG_KEY_SRC_DID, agencyDIDReq), (LOG_KEY_STATUS_DETAIL, statusDetail))
        sndr ! statusDetail
      case e: Exception => handleException(e, sndr)
    }
  }

  def setAgencyRouteInfo(sndr: ActorRef, createdKey: NewKeyCreated): Unit = {
    val setRouteFut = setRoute(createdKey.did)
    setRouteFut map {
      case _: RouteSet =>
        sndr ! AgencyPublicDid(createdKey.did, createdKey.verKey)
      case e =>
        logger.error("could not add route info", (LOG_KEY_SRC_DID, createdKey.did), (LOG_KEY_ERR_MSG, e))
        sndr ! e
    }
  }

  def setEndpoint(): Unit = {
    if (state.isEndpointSet) {
      throw new ForbiddenErrorException()
    } else {
      val ep = buildAgencyEndpoint(agentActorContext.appConfig)
      writeAndApply(EndpointSet())
      updateAgencyEndpointInLedger(ep)
    }
  }

  def updateEndpoint(): Unit = {
    val ep = buildAgencyEndpoint(agentActorContext.appConfig)
    updateAgencyEndpointInLedger(ep)
  }

  def createKey(ck: CreateKey): Unit = {
    if (state.relationship.isEmpty) {
      logger.debug("agency agent key setup starting...")
      setAgentWalletId(entityId)
      val sndr = sender()
      agentActorContext.walletAPI.executeAsync[WalletCreated.type](CreateWallet())(wap)
        .map { _ =>
          agentActorContext.walletAPI.executeAsync[NewKeyCreated](CreateNewKey(seed = ck.seed)).map { kc =>
            self.tell(FinishCreateKey(kc), sndr)
          }
        }
    } else {
      logger.warn("agency agent key setup is already done, returning forbidden response")
      throw new ForbiddenErrorException()
    }
  }

  def finishCreateKey(fck: FinishCreateKey): Unit = {
    val events = List (KeyCreated(fck.createdKey.did, fck.createdKey.verKey))
    writeAndApplyAll(events)
    val maFut = singletonParentProxyActor ? ForKeyValueMapper(AddMapping(AGENCY_DID_KEY, fck.createdKey.did))
    val sndr = sender()
    maFut map {
      case _: MappingAdded =>
        setAgencyRouteInfo(sndr, fck.createdKey)
        self ! SetAgentActorDetail(fck.createdKey.didPair.toAgentDidPair, entityId)
      case e =>
        sndr ! e
    }
    logger.debug("agency agent key setup finished")
  }

  def agencyLedgerDetail(): Ledgers = {
    // Architecture requested that this be future-proofed by assuming Agency will have more than one ledger.
    val genesis = try {
      val genesisFileLocation = appConfig.getStringReq(ConfigConstants.LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION)
      val genesisFileSource = Source.fromFile(genesisFileLocation)
      val lines = genesisFileSource.getLines().toList
      genesisFileSource.close()
      lines
    } catch {
      case e: Exception =>
        logger.error(s"Could not read config ${ConfigConstants.LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION}. Reason: $e")
        List()
    }
    val taaEnabledOnLedger: Boolean = try {
      agentActorContext.poolConnManager.currentTAA match {
        case Some(_) => true
        case None => false
      }
    } catch {
      case e: Exception =>
        logger.error(s"Could not determine if TAA is enabled or not. Assuming it is enabled. Reason: $e")
        true
    }
    val ledgers: Ledgers = List(Map(
        "name" -> "default",
        "genesis" -> genesis,
        "taa_enabled" -> taaEnabledOnLedger
      ))
    ledgers
  }

  //dhh Same note about caching as above.
  def getCachedEndpointFromLedger(did: DidStr, req: Boolean = false): Future[Option[Either[StatusDetail, String]]] = {
    val gep = GetEndpointParam(did, ledgerReqSubmitter)
    val gcop = GetCachedObjectParam(KeyDetail(gep, required = req), LEDGER_GET_ENDPOINT_CACHE_FETCHER)
    getCachedStringValue(did, gcop)
  }

  def getCachedVerKeyFromLedger(did: DidStr, req: Boolean = false): Future[Option[Either[StatusDetail, String]]] = {
    val gvkp = GetVerKeyParam(did, ledgerReqSubmitter)
    val gcop = GetCachedObjectParam(KeyDetail(gvkp, required = req), LEDGER_GET_VER_KEY_CACHE_FETCHER)
    getCachedStringValue(did, gcop)
  }

  def getCachedStringValue(forDid: DidStr, gcop: GetCachedObjectParam): Future[Option[Either[StatusDetail, String]]] = {
    generalCache.getByParamAsync(gcop).map { cqr =>
      cqr.get[String](forDid).map(v => Right(v))
    }.recover {
      case e: Exception =>
        Option(Left(UNHANDLED.withMessage("error while getting value (error-msg: " +
          Exceptions.getErrorMsg(e) + ")")))
    }
  }

  def getAgencyInfo(gad: GetAgencyIdentity, isLocalAgency: Boolean): Future[AgencyInfo] = {
    val vkFut = if (gad.getVerKey)
      if (isLocalAgency) Future(state.myDidAuthKey.flatMap(_.verKeyOpt).map(vk => Right(vk)))
      else getCachedVerKeyFromLedger(gad.did)
    else Future.successful(None)
    val epFut = if (gad.getEndpoint) getCachedEndpointFromLedger(gad.did) else Future.successful(None)
    for (
      vk <- vkFut;
      ep <- epFut
    ) yield {
      AgencyInfo(vk, ep)
    }
  }

  //dhh I don't understand the significance of a local vs. a remote verity.
  def getLocalAgencyInfo(gad: GetAgencyIdentity): Future[AgencyInfo] =
    getAgencyInfo(gad, isLocalAgency = true)

  def getRemoteAgencyIdentity(gad: GetAgencyIdentity): Future[AgencyInfo] =
    getAgencyInfo (gad, isLocalAgency = false)

  def sendAgencyIdentity(gad: GetAgencyIdentity): Unit = {
    logger.debug("send agency detail request received: " + gad)
    val gadFut = if (gad.did == agencyDIDReq) {
      getLocalAgencyInfo(gad)
    } else getRemoteAgencyIdentity(gad)

    val sndr = sender()
    gadFut.map { ai =>
      logger.debug("agency detail sent: " + ai)
      sndr ! ai
    }.recover {
      case e: Exception =>
        logger.error("could not get agency detail", (LOG_KEY_ERR_MSG, e.getMessage))
        throw e
    }
  }

  // Here, a "packed message" is one that's anoncrypted for the agency.
  // Unsealing/unpacking it means decrypting it and finding a "forward" inside.
  // According to Rajesh in mid July 2020, we are not currently using
  // this function (it's dead code).

  //this is in case we directly want to send endpoint requests to
  // agency agent to unseal instead of unsealing it at endpoint layer
  def handlePackedMsg(pmw: PackedMsgWrapper): Unit = {
    val sndr = sender()
    agentMsgTransformer.unpackAsync(
      pmw.msg, KeyParam.fromVerKey(state.myDidAuthKeyReq.verKey), UnpackParam(isAnonCryptedMsg = true)
    ).flatMap { implicit amw =>
      handleUnpackedMsg(pmw)
    }.map { r =>
      sndr ! r
    }.recover {
      case e: Exception =>
        handleException(e, sndr)
    }
  }

  def sendLocalAgencyIdentity(withDetail: Boolean = false): Unit = {
    agencyAgentDetail() match {
      case Some(aad)  =>
        val ledgerDetail = if (withDetail) Option(agencyLedgerDetail()) else None
        sender ! AgencyPublicDid(aad.did, aad.verKey, ledgerDetail)
      case None       => throw new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode)
    }
  }

  override def preAgentStateFix(): Future[Any] = {
    metricsWriter.runWithSpan("preAgentStateFix", "AgencyAgent", InternalSpan) {
      state.myDidAuthKey.map { ak =>
        self ? SetAgentActorDetail(DidPair(ak.keyId, ak.verKeyOpt.getOrElse("")), entityId)
      }.getOrElse {
        Future.successful("post agent actor recovery")
      }
    }
  }

  override def isReadyToHandleIncomingMsg: Boolean = state.isEndpointSet

  lazy val ledgerReqSubmitter: Submitter = Submitter(agencyDIDReq, Some(wap))
  lazy val authedMsgSenderVerKeys: Set[VerKeyStr] = Set.empty

  def ownerDID: Option[DidStr] = state.myDid
  def ownerAgentKeyDIDPair: Option[DidPair] = state.thisAgentAuthKeyDidPair

  //TODO: need to come back to this as in this context doesn't have any relationship information
  override def senderParticipantId(senderVerKey: Option[VerKeyStr]): ParticipantId = UNKNOWN_SENDER_PARTICIPANT_ID

  /**
    * there are different types of actors (agency agent, agency pairwise, user agent and user agent pairwise)
    * when we store the persistence detail, we store these unique id for each of them
    * which then used during routing to know which type of region actor to be used to route the message
    *
    * @return
    */
  override def actorTypeId: Int = ACTOR_TYPE_AGENCY_AGENT_ACTOR
}

//response
case class AgencyInfo(verKey: Option[Either[StatusDetail, VerKeyStr]], endpoint: Option[Either[StatusDetail, String]]) extends ActorMessage {

  def rightOption(v: Either[StatusDetail, String]): Option[String] = v.fold(_ => None, r => Option(r))
  def leftOption(v: Either[StatusDetail, String]): Option[StatusDetail] = v.fold(sd => Option(sd), _ => None)

  def endpointOpt: Option[String] = endpoint.flatMap(rightOption)
  def verKeyOpt: Option[VerKeyStr] = verKey.flatMap(rightOption)

  def endpointErrorOpt: Option[StatusDetail] = endpoint.flatMap(leftOption)
  def verKeyErrorOpt: Option[StatusDetail] = verKey.flatMap(leftOption)

  def verKeyReq: VerKeyStr = verKeyOpt.getOrElse(
    throw new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode, Option("agent not yet created")))

  def isErrorInFetchingVerKey: Boolean = verKey.exists(_.isLeft)
  def isErrorInFetchingEndpoint: Boolean = endpoint.exists(_.isLeft)
  def isErrorFetchingAnyData: Boolean = isErrorInFetchingVerKey || isErrorInFetchingEndpoint
}

case object GetAgencyAgentDetail extends ActorMessage
case class AgencyAgentDetail(did: DidStr, verKey: VerKeyStr, walletId: String) extends ActorMessage {
  def didPair: DidPair = DidPair(did, verKey)
}

//cmds
case class GetLocalAgencyIdentity(withDetail: Boolean = false) extends ActorMessage

/**
 * this message is to get agency identity detail for any agency DID (can be local/self agency or other remote agency)
 * @param did agency did for which need ver key and/or endpoint
 * @param getVerKey determines if ver key needs to be received
 * @param getEndpoint determines if endpoint needs to be received
 */
case class GetAgencyIdentity(did: DidStr, getVerKey: Boolean = true, getEndpoint: Boolean = true) extends ActorMessage

case class CreateKey(seed: Option[String] = None) extends ActorMessage {
  override def toString: String = {
    val redacted = seed.map(_ => "redacted")
    s"CreateKey($redacted)"
  }
}

case object SetEndpoint extends ActorMessage

case object UpdateEndpoint extends ActorMessage

trait AgencyAgentStateImpl extends AgentStateImplBase {
  def domainId: DomainId = relationshipReq.myDid_!
}

trait AgencyAgentStateUpdateImpl
  extends AgentStateUpdateInterface { this : AgencyAgent =>

  override def setAgentWalletId(walletId: String): Unit = {
    state = state.withAgentWalletId(walletId)
  }

  override def setAgencyDIDPair(didPair: DidPair): Unit = {
    state = state.withAgencyDIDPair(didPair)
  }

  def addThreadContextDetail(threadContext: ThreadContext): Unit = {
    state = state.withThreadContext(threadContext)
  }

  def removeThreadContext(pinstId: PinstId): Unit = {
    val afterRemoval = state.currentThreadContexts - pinstId
    state = state.withThreadContext(ThreadContext(afterRemoval))
  }

  def addPinst(pri: ProtocolRunningInstances): Unit = {
    state = state.withProtoInstances(pri)
  }

  override def updateAgencyDidPair(dp: DidPair): Unit = {
    state = state.withAgencyDIDPair(dp)
  }
  override def updateRelationship(rel: Relationship): Unit = {
    state = state.withRelationship(rel)
  }
}

case class FinishCreateKey(createdKey: NewKeyCreated) extends ActorMessage
