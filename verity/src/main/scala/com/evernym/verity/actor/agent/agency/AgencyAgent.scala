package com.evernym.verity.actor.agent.agency

import akka.actor.ActorRef
import akka.event.LoggingReceive
import akka.pattern.ask
import com.evernym.verity.Exceptions.{BadRequestErrorException, ForbiddenErrorException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.actor.agent.relationship.Tags.EDGE_AGENT_KEY
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.relationship.{AnywiseRelationship, RelationshipUtil}
import com.evernym.verity.actor.agent.state.base.{AgentStateImplBase, AgentStateUpdateInterface}
import com.evernym.verity.actor.agent.user.AgentProvisioningDone
import com.evernym.verity.actor.cluster_singleton.{AddMapping, ForKeyValueMapper}
import com.evernym.verity.cache._
import com.evernym.verity.config.CommonConfig
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.ledger.{LedgerPoolConnManager, Submitter, TxnResp}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningDefinition
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.CompleteAgentProvisioning
import com.evernym.verity.util.Util._
import com.evernym.verity.util._
import com.evernym.verity.vault._
import com.evernym.verity.{Exceptions, UrlDetail}

import scala.concurrent.Future
import scala.io.Source
import scala.util.Left


/**
 The actor that represents the agency as an institution.
 Agency services/features are exposed through this agent.
 There is one of these actors per actor system. Contrast AgencyAgentPairwise.
 */
class AgencyAgent(val agentActorContext: AgentActorContext)
  extends AgencyAgentCommon
    with AgencyAgentStateUpdateImpl
    with AgencyPackedMsgHandler
    with AgentSnapshotter[AgencyAgentState] {

  type StateType = AgencyAgentState
  var state = new AgencyAgentState

  override final def receiveAgentCmd: Receive = commonCmdReceiver orElse cmdReceiver

  val cmdReceiver: Receive = LoggingReceive.withLabel("cmdReceiver") {
    case saw: SetAgentActorDetail               => setAgentActorDetail(saw)
    case gad: GetAgencyIdentity                 => sendAgencyIdentity(gad)
    case glai: GetLocalAgencyIdentity           => sendLocalAgencyIdentity(glai.withDetail)
    case ck: CreateKey                          => createKey(ck)
    case SetEndpoint                            => setEndpoint()
    case UpdateEndpoint                         => updateEndpoint()
    case smw: PackedMsgWrapper                  => handlePackedMsg(smw)
    case apd: AgentProvisioningDone             =>
      sendUntypedMsgToProtocol(
        CompleteAgentProvisioning(apd.selfDID, apd.agentVerKey),
        AgentProvisioningDefinition,
        apd.threadId
      )
  }

  override val receiveActorInitSpecificCmd: Receive = LoggingReceive.withLabel("receiveActorInitSpecificCmd") {
    case saw: SetAgentActorDetail               => setAgentActorDetail(saw)
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
      .withAgencyDID(kg.forDID)
      .withThisAgentKeyId(kg.forDID)
    val myDidDoc = RelationshipUtil.prepareMyDidDoc(kg.forDID, kg.forDID, Set(EDGE_AGENT_KEY))
    state = state.withRelationship(AnywiseRelationship(myDidDoc))
  }

  override def getAgencyDIDFut: Future[DID] = state.agencyDID match {
    case None             => Future.failed(new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode))
    case Some(agencyDID)  => Future.successful(agencyDID)
  }

  def updateAgencyEndpointInLedger(url: UrlDetail): Unit = {
    val sndr = sender()
    // This is sort of an example of the problem with futures that Jason wrote about.
    // However, it's not very troubling, because the closure just sends a response;
    // it doesn't modify the actor's state. However, in protocols, when this type of
    // pattern occurs, the state might be modified, and that would be bad.
    val addUrlFutResp = agentActorContext.ledgerSvc.addAttrib(ledgerReqSubmitter, state.myDid_!,
      agentActorContext.ledgerSvc.URL, url.toString)
    addUrlFutResp.map {
      case Right(tr: TxnResp) =>
        logger.debug("url added", (LOG_KEY_SRC_DID, state.myDid_!), ("transaction", tr))
        sndr ! EndpointSet(url.toString)
      case Left(statusDetail: StatusDetail) =>
        logger.error("could not add url", (LOG_KEY_SRC_DID, agencyDIDReq), (LOG_KEY_STATUS_DETAIL, statusDetail))
        sndr ! statusDetail
    }.recover {
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
      setAndCreateAndOpenWallet()
      val createdKey = agentActorContext.walletAPI.createNewKey(CreateNewKeyParam(seed = ck.seed))
      writeAndApply(KeyCreated(createdKey.did))
      val maFut = singletonParentProxyActor ? ForKeyValueMapper(AddMapping(AGENCY_DID_KEY, createdKey.did))
      val sndr = sender()
      maFut map {
        case _: MappingAdded =>
          setAgencyRouteInfo(sndr, createdKey)
          self ! SetAgentActorDetail(createdKey.did, entityId)
        case e =>
          sndr ! e
      }
      logger.debug("agency agent key setup finished")
    } else {
      logger.warn("agency agent key setup is already done, returning forbidden response")
      throw new ForbiddenErrorException()
    }
  }

  def setAndCreateAndOpenWallet(): Unit = {
    setAgentWalletSeed(entityId)
    if (! openWalletIfExists(wap)) {
      agentActorContext.walletAPI.createAndOpenWallet(wap)
    }
  }

  def getAgencyVerKey(did: DID, fromPool: Boolean): VerKey = {
    getVerKeyReqViaCache(did, fromPool)
  }

  def agencyLedgerDetail(): Ledgers = {
    // Architecture requested that this be future-proofed by assuming Agency will have more than one ledger.
    val genesis = try {
      val genesisFileLocation = appConfig.getConfigStringReq(CommonConfig.LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION)
      val genesisFileSource = Source.fromFile(genesisFileLocation)
      val lines = genesisFileSource.getLines().toList
      genesisFileSource.close()
      lines
    } catch {
      case e: Exception =>
        logger.error(s"Could not read config ${CommonConfig.LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION}. Reason: $e")
        List()
    }
    val taaEnabledOnLedger: Boolean = try {
      agentActorContext.poolConnManager.asInstanceOf[LedgerPoolConnManager].currentTAA match {
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

  //dhh I feel a need to understand how caching works. How long before it's reevaluated?
  // How is it invalidated?
  def getCachedVerKeyFromWallet(did: DID): Future[Option[Either[StatusDetail, VerKey]]] = {
    Future.successful(Option(Right(getAgencyVerKey(did, fromPool = GET_AGENCY_VER_KEY_FROM_POOL))))
  }

  //dhh Same note about caching as above.
  def getCachedEndpointFromLedger(did: DID, req: Boolean = false): Future[Option[Either[StatusDetail, String]]] = {
    val gep = GetEndpointParam(did, ledgerReqSubmitter)
    val gcop = GetCachedObjectParam(Set(KeyDetail(gep, required = req)), ENDPOINT_CACHE_FETCHER_ID)
    getCachedStringValue(did, gcop)
  }

  def getCachedVerKeyFromLedger(did: DID, req: Boolean = false): Future[Option[Either[StatusDetail, String]]] = {
    val gvkp = GetVerKeyParam(did, ledgerReqSubmitter)
    val gcop = GetCachedObjectParam(Set(KeyDetail(gvkp, required = req)), VER_KEY_CACHE_FETCHER_ID)
    getCachedStringValue(did, gcop)
  }

  def getCachedStringValue(forDid: DID, gcop: GetCachedObjectParam): Future[Option[Either[StatusDetail, String]]] = {
    agentActorContext.generalCache.getByParamAsync(gcop).mapTo[CacheQueryResponse].map { cqr =>
      cqr.getStringOpt(forDid).map(v => Right(v))
    }.recover {
      case e: Exception =>
        Option(Left(UNHANDLED.withMessage("error while getting value (error-msg: " +
          Exceptions.getErrorMsg(e) + ")")))
    }
  }

  def getAgencyInfo(gad: GetAgencyIdentity, isLocalAgency: Boolean): Future[AgencyInfo] = {
    val vkFut = if (gad.getVerKey)
      if (isLocalAgency) getCachedVerKeyFromWallet(gad.did)
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
  // Unsealing it means decrypting it and finding a "forward" inside.
  // According to Rajesh in mid July 2020, we are not currently using
  // this function (it's dead code).

  //this is in case we directly want to send endpoint requests to
  // agency agent to unseal instead of unsealing it at endpoint layer
  def handlePackedMsg(smw: PackedMsgWrapper): Unit = {
    val sndr = sender()
    processPackedMsg(smw).recover {
      case e: Exception =>
        handleException(e, sndr)
    }
  }

  def sendLocalAgencyIdentity(withDetail: Boolean = false): Unit = {
    state.agencyDID match {
      case Some(agencyDID) =>
        val ledgerDetail = if (withDetail) Option(agencyLedgerDetail()) else None
        sender ! AgencyPublicDid(agencyDID, getAgencyVerKey(agencyDID, fromPool = GET_AGENCY_VER_KEY_FROM_POOL), ledgerDetail)
      case None =>
        throw new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode)
    }
  }

  override def postActorRecoveryCompleted(): List[Future[Any]] = {
    runWithInternalSpan("postActorRecoveryCompleted", "AgencyAgent") {
      List {
        getAgencyDIDFut().map { cqr =>
          cqr.getAgencyDIDOpt.map { aDID =>
            self ? SetAgentActorDetail(aDID, entityId)
          }.getOrElse {
            Future.successful("agency agent not yet created")
          }
        }
      }
    }
  }

  /**
   * this function gets executed post successful actor recovery (meaning all events are applied to state)
   * the purpose of this function is to update any 'LegacyAuthorizedKey' to 'AuthorizedKey'
   */
  override def postSuccessfulActorRecovery(): Unit = {
    super.postSuccessfulActorRecovery()
    state = state
      .relationship
      .map { r =>
        val updatedMyDidDoc = RelationshipUtil.updatedDidDocWithMigratedAuthKeys(state.myDidDoc)
        state.withRelationship(r.update(_.myDidDoc.setIfDefined(updatedMyDidDoc)))
      }
      .getOrElse(state)
  }

  override def isReadyToHandleIncomingMsg: Boolean = state.isEndpointSet

  lazy val ledgerReqSubmitter: Submitter = Submitter(agencyDIDReq, Some(wap))
  lazy val authedMsgSenderVerKeys: Set[VerKey] = Set.empty

  def ownerDID: Option[DID] = state.myDid
  def ownerAgentKeyDID: Option[DID] = state.myDid

  //TODO: need to come back to this as in this context doesn't have any relationship information
  override def senderParticipantId(senderVerKey: Option[VerKey]): ParticipantId = UNKNOWN_SENDER_PARTICIPANT_ID

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
case class AgencyInfo(verKey: Option[Either[StatusDetail, VerKey]], endpoint: Option[Either[StatusDetail, String]]) extends ActorMessageClass {

  def rightOption(v: Either[StatusDetail, String]): Option[String] = v.fold(_ => None, r => Option(r))
  def leftOption(v: Either[StatusDetail, String]): Option[StatusDetail] = v.fold(sd => Option(sd), _ => None)

  def endpointOpt: Option[String] = endpoint.flatMap(rightOption)
  def verKeyOpt: Option[VerKey] = verKey.flatMap(rightOption)

  def endpointErrorOpt: Option[StatusDetail] = endpoint.flatMap(leftOption)
  def verKeyErrorOpt: Option[StatusDetail] = verKey.flatMap(leftOption)

  def verKeyReq: VerKey = verKeyOpt.getOrElse(
    throw new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode, Option("agent not yet created")))

  def isErrorInFetchingVerKey: Boolean = verKey.exists(_.isLeft)
  def isErrorInFetchingEndpoint: Boolean = endpoint.exists(_.isLeft)
  def isErrorFetchingAnyData: Boolean = isErrorInFetchingVerKey || isErrorInFetchingEndpoint
}

//cmds
case class GetLocalAgencyIdentity(withDetail: Boolean = false) extends ActorMessageClass

/**
 * this message is to get agency identity detail for any agency DID (can be local/self agency or other remote agency)
 * @param did agency did for which need ver key and/or endpoint
 * @param getVerKey determines if ver key needs to be received
 * @param getEndpoint determines if endpoint needs to be received
 */
case class GetAgencyIdentity(did: DID, getVerKey: Boolean = true, getEndpoint: Boolean = true) extends ActorMessageClass

case class CreateKey(seed: Option[String] = None) extends ActorMessageClass {
  override def toString: String = {
    val redacted = seed.map(_ => "redacted")
    s"CreateKey($redacted)"
  }
}

case object SetEndpoint extends ActorMessageObject

case object UpdateEndpoint extends ActorMessageObject

trait AgencyAgentStateImpl
  extends AgentStateImplBase {
  def sponsorRel: Option[SponsorRel] = None
}

trait AgencyAgentStateUpdateImpl
  extends AgentStateUpdateInterface { this : AgencyAgent =>

  override def setAgentWalletSeed(seed: String): Unit = {
    state = state.withAgentWalletSeed(seed)
  }

  override def setAgencyDID(did: DID): Unit = {
    state = state.withAgencyDID(did)
  }

  def addThreadContextDetail(threadContext: ThreadContext): Unit = {
    state = state.withThreadContext(threadContext)
  }

  def addPinst(pri: ProtocolRunningInstances): Unit = {
    state = state.withProtoInstances(pri)
  }

  override def setSponsorRel(rel: SponsorRel): Unit = {
    //nothing to do
  }
}