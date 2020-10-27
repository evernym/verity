package com.evernym.verity.actor.agent.user

import akka.event.LoggingReceive
import akka.pattern.ask
import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.MsgPackVersion.{MPV_INDY_PACK, MPV_MSG_PACK, Unrecognized}
import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.actor.agent.relationship.Tags.{CLOUD_AGENT_KEY, EDGE_AGENT_KEY}
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.agency.{SetupCreateKeyEndpoint, SponsorRel}
import com.evernym.verity.actor.agent.msghandler.MsgRespConfig
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgFromDriver}
import com.evernym.verity.actor.agent.msghandler.outgoing._
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.agent.msgsender.{AgentMsgSender, SendMsgParam}
import com.evernym.verity.actor.agent.relationship.RelationshipUtil._
import com.evernym.verity.actor.agent.relationship.{EndpointADT, Endpoints, LegacyRoutingServiceEndpoint, PairwiseRelationship, Relationship, RelationshipUtil, RoutingServiceEndpoint, Tags}
import com.evernym.verity.actor.agent.state.{OwnerDetail, _}
import com.evernym.verity.actor.cluster_singleton.watcher.{AddItem, RemoveItem}
import com.evernym.verity.actor.cluster_singleton.{ForMetricsHelper, ForUserAgentPairwiseActorWatcher, MetricsCountUpdated, UpdateCountMetrics, UpdateFailedAttemptCount, UpdateFailedMsgCount, UpdateUndeliveredMsgCount}
import com.evernym.verity.actor.itemmanager.ItemCommonType.ItemId
import com.evernym.verity.actor.msg_tracer.progress_tracker.MsgParam
import com.evernym.verity.actor.persistence.{Done, InternalReqHelperData}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgWrapper, PackedMsg}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.http.common.RemoteMsgSendingSvc
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.msg_tracer.MsgTraceProvider._
import com.evernym.verity.protocol.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{DID, Parameter, VerKey, _}
import com.evernym.verity.protocol.protocols._
import com.evernym.verity.protocol.protocols.connecting.common._
import com.evernym.verity.protocol.protocols.connecting.v_0_5.{ConnectingMsgFamily => ConnectingMsgFamily_0_5}
import com.evernym.verity.protocol.protocols.connecting.v_0_6.{ConnectingMsgFamily => ConnectingMsgFamily_0_6}
import com.evernym.verity.protocol.protocols.connections.v_1_0.Ctl.TheirDidDocUpdated
import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.{SetupTheirDidDoc, UpdateTheirDid}
import com.evernym.verity.protocol.protocols.connections.v_1_0.{ConnectionsMsgFamily, Ctl}
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.agent.state.base.{LegacyAgentPairwiseStateImpl, LegacyAgentPairwiseStateUpdateImpl}
import com.evernym.verity.config.ConfigUtil.findAgentSpecificConfig
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.{InviteShortened, InviteShorteningFailed, SMSSendingFailed, SMSSent}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.{SendSMSInvite, ShortenInvite}
import com.evernym.verity.texter.SmsInfo
import com.evernym.verity.urlshortener.{DefaultURLShortener, UrlInfo, UrlShortened, UrlShorteningFailed}
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.util.Util.replaceVariables
import com.evernym.verity.util._
import com.evernym.verity.vault._
import org.json.JSONObject

import scala.concurrent.Future
import scala.util.{Failure, Left, Success}

/**
 Represents the part of a user agent that's dedicated to a single pairwise
 relationship.
 */
class UserAgentPairwise(val agentActorContext: AgentActorContext)
  extends UserAgentCommon
    with LegacyAgentPairwiseStateUpdateImpl
    with AgentMsgSender
    with UsesConfigs
    with LegacyPairwiseConnState
    with MsgDeliveryResultHandler
    with MsgNotifierForUserAgentPairwise
    with HasAgentActivity
    with FailedMsgRetrier {

  type StateType = State
  val state = new State
  /**
   * actor persistent state object
   */
  class State
    extends LegacyAgentPairwiseStateImpl
      with OwnerDetail
      with MsgAndDeliveryState
      with Configs {
    override lazy val msgDeliveryState: Option[MsgDeliveryState] = Option(
      new MsgDeliveryState(maxRetryCount, retryEligibilityCriteriaProvider)
    )
    override def initialRel: Relationship = PairwiseRelationship.empty

    override def serializedSize: ParticipantIndex = -1
  }

  override final def agentCmdReceiver: Receive = commonCmdReceiver orElse cmdReceiver

  override def incomingMsgHandler(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, Any] =
    agentCommonMsgHandler orElse agentMsgHandler

  /**
   * handles only those messages supported by this actor (user agent pairwise actor only)
   * @param reqMsgContext request message context
   * @return
   */
  def agentMsgHandler(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, Any] = {
    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, CREATE_MSG_TYPE_GENERAL) =>
      handleCreateMsgGeneral(amw)

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_6, MSG_TYPE_SEND_REMOTE_MSG) =>
      handleSendRemoteMsg(SendRemoteMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, MSG_TYPE_SEND_MSGS) =>
      handleSendMsgs(SendMsgsMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, MSG_TYPE_UPDATE_CONN_STATUS) ||
        amw.isMatched(MFV_0_6, MSG_TYPE_UPDATE_CONN_STATUS) =>
      handleUpdateConnStatusMsg(UpdateConnStatusMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, MSG_TYPE_UPDATE_MSG_STATUS) ||
        amw.isMatched(MFV_0_6, MSG_TYPE_UPDATE_MSG_STATUS) =>
      handleUpdateMsgStatus(UpdateMsgStatusMsgHelper.buildReqMsg(amw))
  }

  /**
   * internal command handlers
   */
  val cmdReceiver: Receive = LoggingReceive.withLabel("cmdReceiver") {
    case saw: SetAgentActorDetail                           => setAgentActorDetail(saw)
    case _: SetupCreateKeyEndpoint if state.myDid.isDefined => throw new BadRequestErrorException(AGENT_ALREADY_CREATED.statusCode)
    case cke: SetupCreateKeyEndpoint                        => handleCreateKeyEndpoint(cke)
    case ppgm: ProcessPersistedSendRemoteMsg                => processPersistedSendRemoteMsg(ppgm)
    case stc: StoreThreadContext                            => handleStoreThreadContext(stc)
    case mss: MsgSentSuccessfully                           => handleMsgSentSuccessfully(mss)
    case msf: MsgSendingFailed                              => handleMsgSendingFailed(msf)
    case s: SetSponsorRel                                   => if (state.sponsorRel.isEmpty) setSponsorDetail(s.rel)
  }

  override final def receiveAgentEvent: Receive =
    //dhh I don't understand this orElse syntax
    commonEventReceiver orElse
      eventReceiver orElse
      pairwiseConnReceiver orElse
      msgState.msgEventReceiver orElse
      legacyEventReceiver orElse
      agentSpecificEventReceiver

  val eventReceiver: Receive = {
    case os: OwnerSetForAgent     =>
      state.setMySelfRelDID(os.ownerDID)
      state.setOwnerAgentKeyDID(os.agentDID)
    case ads: AgentDetailSet      =>
      handleAgentDetailSet(ads)
    case csu: ConnStatusUpdated   =>
      state.setConnectionStatus(state.connectionStatus.map(_.copy(answerStatusCode = csu.statusCode)))
    case sa: SponsorAssigned               =>
      state.setSponsorRel(SponsorRel(sa.id, sa.sponsee))
  }

  //this is for backward compatibility
  val legacyEventReceiver: Receive = {
    case rkd: TheirAgentKeyDlgProofSet =>
      val lrd = LegacyRoutingDetail(null, agentKeyDID = rkd.DID, agentVerKey = rkd.delegatedKey, rkd.signature)
      updateLegacyRelationshipState(null, lrd)

    case rcd: TheirAgentDetailSet =>
      val theirDidDoc = state.relationship.theirDidDoc.map(_.copy(did = rcd.DID))
      val updatedRel = state.relationship
        .update(
          _.thoseDidDocs.setIfDefined(theirDidDoc.map(Seq(_)))
        )
      state.updateRelationship(updatedRel)
      state.setConnectionStatus(ConnectionStatus(reqReceived=true, MSG_STATUS_ACCEPTED.statusCode))

    case rad: TheirAgencyIdentitySet =>
      //TODO This can be more easily done by teaching the LegacyRoutingServiceEndpoint and RoutingServiceEndpoint how to do the conversion.
      val updatedDidDoc = state.relationship.theirDidDoc.map { tdd =>
        val updatedEndpointSeq: Seq[EndpointADT] = tdd.endpoints_!.endpoints.map(_.endpointADTX).map {
          case lep: LegacyRoutingServiceEndpoint => lep.copy(agencyDID = rad.DID)
          case ep: RoutingServiceEndpoint        => ep
          case _                                 => throw new MatchError("unsupported endpoint matched")
        }.map(EndpointADT.apply)
        val updatedEndpoints = Endpoints(updatedEndpointSeq, tdd.endpoints_!.endpointsToAuthKeys)
        tdd.update(_.endpoints := updatedEndpoints)
      }
      val updatedRel = state
        .relationship
        .update(
          _.thoseDidDocs.setIfDefined(updatedDidDoc.map(Seq(_)))
        )
      state.updateRelationship(updatedRel)
      state.setConnectionStatus(ConnectionStatus(reqReceived=true, MSG_STATUS_ACCEPTED.statusCode))
  }

  //TODO: not sure why we have this, we may wanna test and remove this if not needed
  val agentSpecificEventReceiver: Receive = {
    case _ =>
  }

  override lazy val remoteMsgSendingSvc: RemoteMsgSendingSvc = agentActorContext.remoteMsgSendingSvc

  def encParamFromThisAgentToOwner: EncryptParam =
    encParamBuilder
      .withRecipDID(mySelfRelDIDReq)
      .withSenderVerKey(state.thisAgentVerKeyReq)
      .encryptParam

  /**
   * updates thread context (currently this is only for a connecting 0.6 protocol)
   * see comment in 'UserAgent' for function 'handleSendSignalMsg' for more detail
   * @param stc store thread context
   */
  def handleStoreThreadContext(stc: StoreThreadContext): Unit = {
    updateThreadContext(stc.pinstId, stc.threadContext)
    sender ! Done
  }

  def addItemToWatcher(itemId: ItemId): Unit = {
    singletonParentProxyActor ! ForUserAgentPairwiseActorWatcher(AddItem(itemId, None, None))
  }

  def removeItemFromWatcher(itemId: ItemId): Unit = {
    singletonParentProxyActor ! ForUserAgentPairwiseActorWatcher(RemoveItem(itemId))
  }

  def checkIfTheirDidDocExists(): Unit = {
    if (state.theirDidDoc.isEmpty)
      throw new BadRequestErrorException(CONNECTION_DOES_NOT_EXIST.statusCode, Option("connection not yet established/completed"))
  }

  def checkMsgSenderIfConnectionIsNotYetEstablished(msgSenderVerKey: VerKey): Unit = {
    if (state.theirDidDoc.isEmpty) checkIfMsgSentByAuthedMsgSenders(msgSenderVerKey)
  }

  def handleAgentDetailSet(ad: AgentDetailSet): Unit = {
    state.setThisAgentKeyId(ad.agentKeyDID)
    val isThisAnEdeAgent = ad.forDID == ad.agentKeyDID
    val agentKeyTags: Set[Tags] = if (isThisAnEdeAgent) Set(EDGE_AGENT_KEY) else Set(CLOUD_AGENT_KEY)
    val myDidDoc = RelationshipUtil.prepareMyDidDoc(ad.forDID, ad.agentKeyDID, agentKeyTags)
    state.setRelationship(PairwiseRelationship("pairwise", Option(myDidDoc)))
    if (! isThisAnEdeAgent) {
      state.addNewAuthKeyToMyDidDoc(ad.forDID, Set(EDGE_AGENT_KEY))
    }
  }

  def authedMsgSenderVerKeys: Set[VerKey] = {
    val authedDIDS = (
      ownerAgentKeyDID  ++                          //owner agent (if internal msgs are sent encrypted)
      state.myDid ++                                //this edge pairwise DID
      state.theirDid ++                             //their pairwise DID
      state.theirAgentKeyDID                        //their pairwise agent key DID
    ).toSet
    authedDIDS.filter(_.nonEmpty).map(getVerKeyReqViaCache(_))
  }

  def retryEligibilityCriteriaProvider(): RetryEligibilityCriteria = {
    (state.myDid, state.theirDidDoc.isDefined) match {
      case (Some(myPairwiseDID), true) =>
        RetryEligibilityCriteria(senderDID = Option(myPairwiseDID),
          Set(CREATE_MSG_TYPE_CONN_REQ, CREATE_MSG_TYPE_CONN_REQ_ANSWER),
          Set(theirRoutingTarget))
      case _                           => RetryEligibilityCriteria()
    }
  }

  override def updateUndeliveredMsgCountMetrics(): Unit = {
    msgState.msgDeliveryState.foreach { mds =>
      sendUpdateMetrics(UpdateUndeliveredMsgCount(entityId, AS_USER_AGENT_MSG_UNDELIVERED_COUNT, mds.getUndeliveredMsgCounts))
    }
  }

  override def updateFailedAttemptCountMetrics(): Unit = {
    msgState.msgDeliveryState.foreach { mds =>
      sendUpdateMetrics(UpdateFailedAttemptCount(entityId, AS_USER_AGENT_MSG_FAILED_ATTEMPT_COUNT, mds.getFailedAttemptCounts))
    }
  }

  override def updateFailedMsgCountMetrics(): Unit = {
    msgState.msgDeliveryState.foreach { mds =>
      sendUpdateMetrics(UpdateFailedMsgCount(entityId, AS_USER_AGENT_MSG_FAILED_COUNT, mds.getFailedMsgCounts))
    }
  }

  def sendUpdateMetrics(msg: UpdateCountMetrics): Unit = {
    val failedMsgCountMetricFut = singletonParentProxyActor ? ForMetricsHelper(msg)
    failedMsgCountMetricFut.map {
      case r: MetricsCountUpdated =>
        logger.debug(s"successfully updated ${r.metricsName}: " + r.totalCount)
    }.recover {
      case e: Exception => logger.error("error while updating metrics: " + Exceptions.getErrorMsg(e))
    }
  }

  override def msgSentSuccessfully(mss: MsgSentSuccessfully): Unit = {
    self ! mss
  }

  override def msgSendingFailed(msf: MsgSendingFailed): Unit = {
    self ! msf
  }

  def handleMsgSentSuccessfully(mss: MsgSentSuccessfully): Unit = {
    notifyUserForSuccessfulMsgDelivery(NotifyMsgDetail(mss.uid, mss.typ), updateDeliveryStatus = false)
  }

  def handleMsgSendingFailed(msf: MsgSendingFailed): Unit = {
    notifyUserForFailedMsgDelivery(NotifyMsgDetail(msf.uid, msf.typ), updateDeliveryStatus = false)
  }

  def stateDetailsFor: Future[PartialFunction[String, Parameter]] = {
    val getConnectEndpointDetail: String = {
      val msg = ActorEndpointDetail(userAgentPairwiseRegionName, entityId)
      DefaultMsgCodec.toJson(msg)
    }
    for (
      agencyVerKey    <- getAgencyVerKeyFut;
      filteredConfigs <- getConfigs(Set(NAME_KEY, LOGO_URL_KEY))
    ) yield {
      {
        case SELF_ID                                => Parameter(SELF_ID, state.myDid_!)
        case OTHER_ID                               => Parameter(OTHER_ID, state.theirDid.getOrElse(UNKNOWN_OTHER_ID))
        case AGENCY_DID                             => Parameter(AGENCY_DID, agencyDIDReq)
        case AGENCY_DID_VER_KEY                     => Parameter(AGENCY_DID_VER_KEY, agencyVerKey)
        case MY_SELF_REL_DID                        => Parameter(MY_SELF_REL_DID, mySelfRelDIDReq)
        case MY_PAIRWISE_DID                        => Parameter(MY_PAIRWISE_DID, state.myDid_!)
        case MY_PAIRWISE_DID_VER_KEY                => Parameter(MY_PAIRWISE_DID_VER_KEY, myPairwiseVerKey)
        case THEIR_PAIRWISE_DID                     => Parameter(THEIR_PAIRWISE_DID, state.theirDid_!)

        case THIS_AGENT_VER_KEY                     => Parameter(THIS_AGENT_VER_KEY, state.thisAgentVerKeyReq)
        case THIS_AGENT_WALLET_SEED                 => Parameter(THIS_AGENT_WALLET_SEED, agentWalletSeedReq)

        case NAME                                   => Parameter(NAME, agentName(filteredConfigs.configs))
        case LOGO_URL                               => Parameter(LOGO_URL, agentLogoUrl(filteredConfigs.configs))
        case CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON  => Parameter(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON, getConnectEndpointDetail)

        //this is legacy way of how public DID is being handled
        //'ownerDIDReq' is basically a self relationship id
        // (which may be wrong to be used as public DID, but thats how it is being used so far)
        // we should do some long term backward/forward compatible fix may be
        case MY_PUBLIC_DID                          => Parameter(MY_PUBLIC_DID, mySelfRelDIDReq)
      }
    }
  }

  def getSenderDIDBySenderVerKey(verKey: VerKey): DID = {
    //TODO: Not sure if this is a good way to determine actual sender, need to come back to this
    if (isTheirAgentVerKey(verKey)) {
      state.theirDid_!
    } else {
      state.myDid_!
    }
  }

  def getEncParamBasedOnMsgSender(implicit reqMsgContext: ReqMsgContext): EncryptParam = {
    encParamBasedOnMsgSender(reqMsgContext.latestDecryptedMsgSenderVerKey)
  }

  override def postUpdateConfig(tupdateConf: TypedMsg[UpdateConfigReqMsg], senderVerKey: Option[VerKey]): Unit = {
    agentCache.deleteFromCache(tupdateConf.msg.configs.map(_.name))
    val configName = expiryTimeInSecondConfigNameForMsgType(CREATE_MSG_TYPE_CONN_REQ)

    tupdateConf.msg.configs.filter(_.name == configName).foreach { c =>
      val msgs = Set(
        UpdateMsgExpirationTime_MFV_0_5(CREATE_MSG_TYPE_CONN_REQ, c.value.toInt),
        UpdateMsgExpirationTime_MFV_0_6(CREATE_MSG_TYPE_CONN_REQ, c.value.toInt))
      msgs.foreach { msg =>
        sendTypedMsgToProtocol(msg.typedMsg, relationshipId, DEFAULT_THREAD_ID,
          senderParticipantId(senderVerKey), Option(MsgRespConfig(isSyncReq = true)), None, None)
      }
    }
  }

  def handleCreateMsgGeneral(amw: AgentMsgWrapper)(implicit reqMsgContext: ReqMsgContext): Unit = {
    runWithInternalSpan("handleCreateMsgGeneral", "UserAgentPairwise") {
      val createMsgReq = amw.headAgentMsg.convertTo[CreateMsgReqMsg_MFV_0_5]

      addUserResourceUsage(reqMsgContext.clientIpAddressReq, RESOURCE_TYPE_MESSAGE,
        s"${MSG_TYPE_CREATE_MSG}_${createMsgReq.mtype}", Option(domainId))
      val msgDetail = amw.tailAgentMsgs.head.convertTo[GeneralCreateMsgDetail_MFV_0_5]

      val srm = SendRemoteMsg(amw.headAgentMsgDetail, createMsgReq.uid.getOrElse(getNewMsgUniqueId),
        createMsgReq.mtype, msgDetail.`@msg`,
        createMsgReq.sendMsg, createMsgReq.thread, createMsgReq.replyToMsgId,
        msgDetail.title, msgDetail.detail, msgDetail.senderName, msgDetail.senderLogoUrl)
      logger.debug("process received msg: " + srm)

      handleSendRemoteMsg(srm)
    }
  }

  def handleSendRemoteMsg(sendRemoteMsg: SendRemoteMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    runWithInternalSpan("handleSendRemoteMsg", "UserAgentPairwise") {
      //tracking related
      MsgProgressTracker.recordInMsgProcessingStarted(
      trackingParam = trackingParam.copy(threadId = sendRemoteMsg.threadOpt.flatMap(_.thid)),
      inMsgParam = MsgParam(msgId =  Option(sendRemoteMsg.id), msgName = Option(sendRemoteMsg.mtype)))
      validateAndProcessSendRemoteMsg(ValidateAndProcessSendRemoteMsg(sendRemoteMsg, getInternalReqHelperData))
    }
  }

  def buildMsgAnsweredEvt(uid: MsgId, newStatusCode: String, refMsgId: Option[String]=None): MsgAnswered = {
    MsgAnswered(uid, newStatusCode, refMsgId.orNull, getMillisForCurrentUTCZonedDateTime)
  }

  def writeMsgDetail(uid: MsgId, name: String, valueOpt: Option[String], useAsyncPersist: Boolean): Unit = {
    valueOpt.foreach { value =>
      if (useAsyncPersist)
        asyncWriteAndApply(MsgDetailAdded(uid, name, value))
      else
        writeAndApply(MsgDetailAdded(uid, name, value))
    }
  }

  def writeSendRemoteMsgDetail(uid: MsgId, sendRemoteMsg: SendRemoteMsg, useAsyncPersist: Boolean): Unit = {
    writeMsgDetail(uid, TITLE, sendRemoteMsg.title, useAsyncPersist)
    writeMsgDetail(uid, DETAIL, sendRemoteMsg.detail, useAsyncPersist)
    writeMsgDetail(uid, NAME_KEY, sendRemoteMsg.senderName, useAsyncPersist)
    writeMsgDetail(uid, LOGO_URL_KEY, sendRemoteMsg.senderLogoUrl, useAsyncPersist)
  }

  def processPersistedSendRemoteMsg(ppsrm: ProcessPersistedSendRemoteMsg): Unit = {
    runWithInternalSpan("processPersistedSendRemoteMsg", "UserAgentPairwise") {
      MsgProgressTracker.recordLegacyRespMsgPackagingStarted(
        respMsgId = Option(ppsrm.msgCreated.uid))(ppsrm.reqHelperData.reqMsgContext)
      implicit val reqMsgContext: ReqMsgContext = ppsrm.reqHelperData.reqMsgContext
      val msgCreatedResp = SendRemoteMsgHelper.buildRespMsg(ppsrm.msgCreated.uid)(reqMsgContext.agentMsgContext)
      val otherRespMsgs = if (ppsrm.sendRemoteMsg.sendMsg) sendMsgV1(List(ppsrm.msgCreated.uid)) else List.empty

      val wrapInBundledMsg = reqMsgContext.agentMsgContext.msgPackVersion == MPV_MSG_PACK
      val param = AgentMsgPackagingUtil.buildPackMsgParam(getEncParamBasedOnMsgSender, msgCreatedResp ++ otherRespMsgs, wrapInBundledMsg)
      logger.debug("param (during general proof/cred msgs): " + param)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackVersion, param)(agentMsgTransformer, wap)
      sender ! rp
    }
  }

  lazy val useAsyncPersistForMsgForward: Boolean =
    appConfig.getConfigBooleanOption(PERSISTENCE_USE_ASYNC_MSG_FORWARD).getOrElse(false)

  def persistAndProcessSendRemoteMsg(papsrm: PersistAndProcessSendRemoteMsg): Unit = {
    runWithInternalSpan("persistAndProcessSendRemoteMsg", "UserAgentPairwise") {
      implicit val reqMsgContext: ReqMsgContext = papsrm.reqHelperData.reqMsgContext

      val senderDID = getSenderDIDBySenderVerKey(reqMsgContext.latestDecryptedMsgSenderVerKeyReq)

      val payloadParam = StorePayloadParam(papsrm.sendRemoteMsg.`@msg`, None)
      val msgStored = storeMsg(papsrm.sendRemoteMsg.id, papsrm.sendRemoteMsg.mtype,
        state.myDid_!, senderDID, papsrm.sendRemoteMsg.sendMsg, papsrm.sendRemoteMsg.threadOpt,
        Option(payloadParam), useAsyncPersistForMsgForward)

      val msgStatusUpdatedEvt = papsrm.sendRemoteMsg.replyToMsgId.map { replyToMsgId =>
        buildMsgAnsweredEvt(replyToMsgId, MSG_STATUS_ACCEPTED.statusCode, Option(msgStored.msgCreatedEvent.uid))
      }
      writeSendRemoteMsgDetail(msgStored.msgCreatedEvent.uid, papsrm.sendRemoteMsg, useAsyncPersistForMsgForward)
      msgStatusUpdatedEvt.foreach { evt =>
        if (useAsyncPersistForMsgForward) asyncWriteAndApply(evt)
        else writeAndApply(evt)
      }
      self tell(ProcessPersistedSendRemoteMsg(papsrm.sendRemoteMsg, msgStored.msgCreatedEvent, getInternalReqHelperData), sender())
    }
  }

  def validateAndProcessSendRemoteMsg(vapsrm: ValidateAndProcessSendRemoteMsg): Unit = {
    runWithInternalSpan("validateAndProcessSendRemoteMsg", "UserAgentPairwise") {
      implicit val reqMsgContext: ReqMsgContext = vapsrm.reqHelperData.reqMsgContext
      checkIfTheirDidDocExists()
      checkMsgSenderIfConnectionIsNotYetEstablished(reqMsgContext.latestDecryptedMsgSenderVerKeyReq)
      msgState.checkIfMsgExists(vapsrm.sendRemoteMsg.replyToMsgId)
      vapsrm.sendRemoteMsg.replyToMsgId.foreach(state.checkIfMsgAlreadyNotInAnsweredState)
      persistAndProcessSendRemoteMsg(PersistAndProcessSendRemoteMsg(vapsrm.sendRemoteMsg, getInternalReqHelperData))
    }
  }

  def handleSendMsgs(sendMsgReq: SendMsgsReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    addUserResourceUsage(reqMsgContext.clientIpAddressReq,
      RESOURCE_TYPE_MESSAGE, MSG_TYPE_SEND_MSGS, Option(domainId))
    val msgSentRespMsg = sendMsgV1(sendMsgReq.uids.map(uid => uid))
    val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, msgSentRespMsg)
    val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackVersion, param)(agentMsgTransformer, wap)
    sender ! rp
  }

  def logErrorsIfFutureFails(f: Future[Any], op: String): Unit = {
    f.recover {
      case e: Exception =>
        logger.error("operation execution failed", ("operation", op), (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
    }
  }

  def sendMsgV1(uids: List[String])(implicit reqMsgContext: ReqMsgContext): List[Any] = {
    runWithInternalSpan("sendMsgV1", "UserAgentPairwise") {
      uids.foreach { uid =>
        val msg = msgState.getMsgReq(uid)
        logErrorsIfFutureFails(Future(sendGeneralMsg(uid)),
          s"send messages to their agent [uid = $uid, type= ${msg.getType}]")
      }
      SendMsgsMsgHelper.buildRespMsg(uids)(reqMsgContext.agentMsgContext)
    }
  }

  def sendGeneralMsg(uid: MsgId)
                    (implicit reqMsgContext: ReqMsgContext): Unit = {
    runWithInternalSpan("sendGeneralMsg", "UserAgentPairwise") {
      msgState.getMsgOpt(uid).foreach { msg =>
        val sentBySelf = msg.senderDID == state.myDid_!
        val result = if (sentBySelf) {
          self ! UpdateMsgDeliveryStatus(uid,
            theirRoutingTarget, MSG_DELIVERY_STATUS_PENDING.statusCode, None)
          sendMsgToTheirAgent(uid, isItARetryAttempt = false, reqMsgContext.agentMsgContext.msgPackVersion)
        } else {
          sendToUser(uid)
        }
        val replyToMsgId = msgState.getReplyToMsgId(uid)
        MsgProgressTracker.recordLegacyRespMsgPackagingFinished(
          outMsgParam = MsgParam(msgId = Option(uid), msgName = Option(msg.getType), replyToMsgId = replyToMsgId))
        val nextHop = if (sentBySelf) NEXT_HOP_THEIR_ROUTING_SERVICE else NEXT_HOP_MY_EDGE_AGENT
        MsgRespTimeTracker.recordMetrics(reqMsgContext.id, msg.getType, nextHop)
        result.onComplete {
          case Success(_) => MsgProgressTracker.recordLegacyMsgSentToNextHop(nextHop)
          case Failure(e) => MsgProgressTracker.recordLegacyMsgSendingFailed(nextHop, e.getMessage)
        }
      }
    }
  }

  def buildLegacySendRemoteMsg_MFV_0_5(uid: MsgId, fc: AgentConfigs): List[Any] = {
    runWithInternalSpan("buildLegacySendRemoteMsg_MFV_0_5", "UserAgentPairwise") {
      val msg = msgState.getMsgReq(uid)
      val replyToMsgId = msgState.getReplyToMsgId(uid)
      val mds = msgState.getMsgDetails(uid)
      val payloadWrapper = msgState.getMsgPayloadReq(uid)
      buildLegacySendRemoteMsg_MFV_0_5(uid, msg.getType, payloadWrapper.msg, replyToMsgId, mds, msg.thread, fc)
    }
  }

  def buildLegacySendRemoteMsg_MFV_0_5(msgId: MsgId,
                                       msgType: String,
                                       payload: Array[Byte],
                                       replyToMsgId: Option[MsgId],
                                       msgDetail: Map[AttrName, AttrValue]=Map.empty,
                                       threadOpt: Option[MsgThread]=None,
                                       configsWrapper: AgentConfigs=AgentConfigs(Set.empty)): List[Any] = {

    val title = msgDetail.get(TITLE)
    val detail = msgDetail.get(DETAIL)
    val name = msgDetail.get(NAME_KEY) orElse configsWrapper.configs.find(_.name == NAME_KEY).map(_.value)
    val logoUrl = msgDetail.get(LOGO_URL_KEY) orElse configsWrapper.configs.find(_.name == LOGO_URL_KEY).map(_.value)

    List(
      CreateMsgReqMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG, MTV_1_0), msgType,
        uid = Option(msgId), replyToMsgId = replyToMsgId, threadOpt, sendMsg=true),
      GeneralCreateMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, MTV_1_0),
        payload, title, detail, name, logoUrl)
    )
  }

  def buildSendRemoteMsg_MFV_0_6(uid: MsgId): List[Any] = {
    runWithInternalSpan("buildSendRemoteMsg_MFV_0_6", "UserAgentPairwise") {
      val msg = msgState.getMsgReq(uid)
      val replyToMsgId = msgState.getReplyToMsgId(uid)
      val mds = msgState.getMsgDetails(uid)
      val title = mds.get(TITLE)
      val detail = mds.get(DETAIL)
      val payloadMsg = msgState.getMsgPayloadReq(uid).msg
      val nativeMsg = SendRemoteMsgReq_MFV_0_6(MSG_TYPE_DETAIL_SEND_REMOTE_MSG, uid,
        msg.getType, new JSONObject(new String(payloadMsg)), sendMsg=msg.sendMsg, msg.thread, title, detail, replyToMsgId)
      List(nativeMsg)
    }
  }

  def buildSendMsgParam(uid: MsgId, msgType: String, msg: Array[Byte], isItARetryAttempt: Boolean): SendMsgParam = {
    SendMsgParam(uid, msgType, msg, agencyDIDReq, theirRoutingParam, isItARetryAttempt)
  }

  def sendMsgToTheirAgent(uid: String, isItARetryAttempt: Boolean): Future[Any] = {
    val msgPackVersion = msgState.getMsgPayload(uid).flatMap(_.msgPackVersion).getOrElse(MPV_MSG_PACK)
    sendMsgToTheirAgent(uid, isItARetryAttempt, msgPackVersion)
  }

  //target msg needs to be prepared/packed
  def sendMsgToTheirAgent(uid: MsgId, isItARetryAttempt: Boolean, mpv: MsgPackVersion): Future[Any] = {
    runWithInternalSpan("sendMsgToTheirAgent", "UserAgentPairwise") {
      val msg = msgState.getMsgReq(uid)
      val payload = msgState.getMsgPayload(uid)
      logger.debug("msg building started", (LOG_KEY_UID, uid))
      getAgentConfigs(Set(GetConfigDetail(NAME_KEY, req = false), GetConfigDetail(LOGO_URL_KEY, req = false))).flatMap { fc: AgentConfigs =>
        logger.debug("got required configs", (LOG_KEY_UID, uid))
        (theirRoutingDetail, payload) match {
          case (Some(Left(_: LegacyRoutingDetail)), _) =>
            val agentMsgs = mpv match {
              case MPV_MSG_PACK   => buildLegacySendRemoteMsg_MFV_0_5(uid, fc)
              case MPV_INDY_PACK  => buildSendRemoteMsg_MFV_0_6(uid)
              case x              => throw new RuntimeException("unsupported msg pack version: " + x)
            }
            buildAndSendMsgToTheirRoutingService(uid, msg.getType, agentMsgs, mpv)
          case (Some(Right(_: RoutingDetail)), Some(p)) =>
            Future.successful(buildRoutedPackedMsgForTheirRoutingService(mpv, p.msg, msg.`type`))
          case x => throw new RuntimeException("unsupported condition: " + x)
        }
      }.recover {
        case e: Exception =>
          logger.error("send msg failed unexpectedly",
            (LOG_KEY_UID, uid),
            (LOG_KEY_MESSAGE_TYPE, msg.getType),
            (LOG_KEY_MESSAGE_CREATION_DATE_TIME, msg.creationDateTime.toString),
            (LOG_KEY_MESSAGE_LAST_UPDATED_DATE_TIME, msg.lastUpdatedDateTime.toString),
            (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
          throw e
      }
   }
  }

  //target msg is already packed and it needs to be packed inside general msg wrapper
  def buildAndSendGeneralMsgToTheirRoutingService(smp: SendMsgParam,
                                                  thread: Option[MsgThread]=None): Future[Any] = {
    val packedMsg = if (smp.theirRoutingParam.route.isLeft) {
      val agentMsgs = buildLegacySendRemoteMsg_MFV_0_5(smp.uid, smp.msgType, smp.msg, None, Map.empty, thread)
      buildReqMsgForTheirRoutingService(MPV_MSG_PACK, agentMsgs, wrapInBundledMsgs = true, smp.msgType)
    } else {
      buildRoutedPackedMsgForTheirRoutingService(MPV_INDY_PACK, smp.msg, smp.msgType)
    }
    sendToTheirAgencyEndpoint(smp.copy(msg=packedMsg.msg))
  }

  //final target msg is ready and needs to be packed as per their routing service
  def buildAndSendMsgToTheirRoutingService(uid: MsgId,
                                           msgType: String,
                                           agentMsgs: List[Any],
                                           msgPackVersion: MsgPackVersion): Future[Any] = {
    runWithInternalSpan("buildAndSendMsgToTheirRoutingService", "UserAgentPairwise") {
      val packedMsg = buildReqMsgForTheirRoutingService(msgPackVersion, agentMsgs, wrapInBundledMsgs = true, msgType)
      logger.debug("agency msg prepared", (LOG_KEY_UID, uid))
      sendToTheirAgencyEndpoint(buildSendMsgParam(uid, msgType, packedMsg.msg, isItARetryAttempt = false))
    }
  }

  def sendToUser(uid: MsgId): Future[Any] = {
    if (! state.isConnectionStatusEqualTo(CONN_STATUS_DELETED.statusCode)) {
      val msg = msgState.getMsgReq(uid)
      notifyUserForNewMsg(NotifyMsgDetail(uid, msg.getType), updateDeliveryStatus = true)
    } else {
      val errorMsg = s"connection is marked as DELETED, user won't be notified about this msg: " + uid
      logger.warn("user agent pairwise", "notify user",
        s"connection is marked as DELETED, user won't be notified about this msg", uid)
      Future.failed(new RuntimeException(errorMsg))
    }
  }

  def handleUpdateConnStatusMsg(updateConnStatus: UpdateConnStatusReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    addUserResourceUsage(reqMsgContext.clientIpAddressReq, RESOURCE_TYPE_MESSAGE,
      MSG_TYPE_UPDATE_CONN_STATUS, Option(domainId))
    if (updateConnStatus.statusCode != CONN_STATUS_DELETED.statusCode) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option(s"invalid status code value: ${updateConnStatus.statusCode}"))
    }
    writeAndApply(ConnStatusUpdated(updateConnStatus.statusCode))
    val connectionStatusUpdatedRespMsg = UpdateConnStatusMsgHelper.buildRespMsg(updateConnStatus.statusCode)(reqMsgContext.agentMsgContext)
    val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, connectionStatusUpdatedRespMsg)
    val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackVersion, param)(agentMsgTransformer, wap)
    sendRespMsg(rp)
  }

  def getResourceName(msgName: String): String = {
    msgName match {
      case CREATE_MSG_TYPE_CONN_REQ => s"${MSG_TYPE_CREATE_MSG}_$msgName"
      case CREATE_MSG_TYPE_CONN_REQ_ANSWER => s"${MSG_TYPE_CREATE_MSG}_$msgName"
      case x => x
    }
  }

  val unAllowedLegacyConnectingMsgNames: Set[String] = Set(
    CREATE_MSG_TYPE_CONN_REQ_ANSWER,
    MSG_TYPE_CONN_REQ_ACCEPTED, MSG_TYPE_CONN_REQ_DECLINED,
    CREATE_MSG_TYPE_CONN_REQ_REDIRECTED, MSG_TYPE_CONN_REQ_REDIRECTED)

  val unAllowedConnectionsMsgNames: Set[String] = Set("request")

  override def allowedUnauthedMsgTypes: Set[MsgType] =
    unAllowedLegacyConnectingMsgNames.map(ConnectingMsgFamily_0_5.msgType) ++
      unAllowedLegacyConnectingMsgNames.map(ConnectingMsgFamily_0_6.msgType) ++
      unAllowedConnectionsMsgNames.map(ConnectionsMsgFamily.msgType)

  def handleCreateKeyEndpoint(scke: SetupCreateKeyEndpoint): Unit = {
    scke.pid.foreach { pd =>
      writeAndApply(ProtocolIdDetailSet(pd.protoRef.msgFamilyName, pd.protoRef.msgFamilyVersion, pd.pinstId))
    }
    scke.ownerAgentActorEntityId.foreach(setAgentWalletSeed)
    val odsEvt = OwnerSetForAgent(scke.mySelfRelDID, scke.ownerAgentKeyDID.get)
    val cdsEvt = AgentDetailSet(scke.forDID, scke.newAgentKeyDID)
    writeAndApply(odsEvt)
    writeAndApply(cdsEvt)
    val sndr = sender()

    val setRouteFut = setRoute(scke.forDID, Option(scke.newAgentKeyDID))
    val updateUserAgentFut = scke.ownerAgentKeyDID match {
      case Some(ad) => agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(ad, cdsEvt))
      case _        => Future.failed(new RuntimeException("ownerAgentKeyDID not provided"))
    }

    Future.sequence(Set(setRouteFut, updateUserAgentFut)).map { _ =>
      sndr ! PairwiseConnSet
    }
  }

  override def updateMsgDeliveryStatus(uid: MsgId, to: String,
                                       statusCode: String, statusMsg: Option[String]=None): Unit = {
    val uds = UpdateMsgDeliveryStatus(uid, to, statusCode, statusMsg)
    self ! uds
  }

  override def senderParticipantId(senderVerKey: Option[VerKey]): ParticipantId = {
    val edgeVerKey = state.myDid.map(did => getVerKeyReqViaCache(did))
    val otherEntityEdgeVerKey = state.theirDid.map(d => getVerKeyReqViaCache(d))

    val senderParticipantId = senderVerKey match {
      case Some(svk) if otherEntityEdgeVerKey.contains(svk) =>
        ParticipantUtil.participantId(state.theirDid_!, None)    //inter domain (sent from other entity's edge agent)
      case Some(svk) if edgeVerKey.contains(svk) =>
        ParticipantUtil.participantId(state.thisAgentKeyDIDReq, Option(domainId))          //intra domain
      case Some(_) =>
        //this is during msg exchanges done with send msg api between different agencies
        ParticipantUtil.participantId(UNKNOWN_SENDER_PARTICIPANT_ID, None)
      case None =>
        //there are below few cases where we don't have sender ver key available
        //get msgs sent from user agent actor (internal msgs)
        //get invite detail msg is another example
        //for now, we can go ahead with this unknown sender participant id
        //later on we may have to come back and find out better solution for this
        ParticipantUtil.participantId(UNKNOWN_SENDER_PARTICIPANT_ID, None)
    }
    logger.trace("msg sender finding => ver key: " + senderVerKey)
    logger.trace("msg sender finding => edgeVerKey: " + edgeVerKey)
    logger.trace("msg sender finding => otherEntityEdgeVerKey: " + otherEntityEdgeVerKey)
    logger.trace("msg sender finding => participant id: " + senderParticipantId)
    senderParticipantId
  }

  override def sendMsgToOtherEntity(omp: OutgoingMsgParam,
                                    msgId: MsgId,
                                    msgName: MsgName,
                                    thread: Option[MsgThread]=None): Future[Any] = {
    logger.debug("about to send stored msg to other entity: " + msgId)
    omp.givenMsg match {

      case pm: PackedMsg =>
        val sendMsgParam: SendMsgParam = buildSendMsgParam(msgId, msgName, pm.msg, isItARetryAttempt=false)
        buildAndSendGeneralMsgToTheirRoutingService(sendMsgParam, thread)

      case _: JsonMsg =>
        //between cloud agents, we don't support sending json messages
        ???
    }
  }

  def futureNone: Future[None.type] = Future.successful(None)

  override def handleSpecificSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = {
    //pinst (legacy connecting protocol) -> connecting actor driver -> this actor
    case SignalMsgFromDriver(_: ConnReqReceived, _, _, _)                 => writeAndApply(ConnectionStatusUpdated(reqReceived = true)); futureNone
    //pinst (legacy connecting protocol) -> connecting actor driver -> this actor
    case SignalMsgFromDriver(cc: ConnectionStatusUpdated, _, _, _)        => writeAndApply(cc); futureNone
    //pinst (legacy connecting protocol) -> connecting actor driver -> this actor
    case SignalMsgFromDriver(nu: NotifyUserViaPushNotif, _, _, _)         => notifyUser(nu); futureNone
    //pinst (legacy connecting protocol) -> connecting actor driver -> this actor
    case SignalMsgFromDriver(sm: SendMsgToRegisteredEndpoint, _, _, _)    => sendAgentMsgToRegisteredEndpoint(sm)
    //pinst (connections 1.0) -> connections actor driver -> this actor
    case SignalMsgFromDriver(dc: SetupTheirDidDoc, _, _, _)               => handleUpdateTheirDidDoc(dc)
    //pinst (connections 1.0) -> connections actor driver -> this actor
    case SignalMsgFromDriver(utd: UpdateTheirDid, _, _, _)                => handleUpdateTheirDid(utd)
    //pinst (relationship 1.0) -> relationship actor driver -> this actor
    case SignalMsgFromDriver(si: ShortenInvite, _, _, _)                  => handleShorteningInvite(si)
    //pinst (relationship 1.0) -> relationship actor driver -> this actor
    case SignalMsgFromDriver(ssi: SendSMSInvite, _, _, _)                 => handleSendingSMSInvite(ssi)
  }

  def handleUpdateTheirDid(utd: UpdateTheirDid):Future[Option[ControlMsg]] = {
    writeAndApply(TheirDidUpdated(utd.theirDID))
    Future.successful(Option(ControlMsg(Ctl.TheirDidUpdated())))
  }

  def handleShorteningInvite(si: ShortenInvite): Future[Option[ControlMsg]] = {
    context.actorOf(DefaultURLShortener.props(appConfig)) ? UrlInfo(si.inviteURL) map {
      case UrlShortened(shortUrl) => Option(ControlMsg(InviteShortened(si.invitationId, si.inviteURL, shortUrl)))
      case UrlShorteningFailed(_, msg) => Option(ControlMsg(InviteShorteningFailed(si.invitationId, msg)))
    }
  }

  def handleSendingSMSInvite(ssi: SendSMSInvite): Future[Option[ControlMsg]] = {
    context.actorOf(DefaultURLShortener.props(appConfig)) ? UrlInfo(ssi.inviteURL) flatMap {
      case UrlShorteningFailed(_, msg) =>
        Future.successful(
          Option(ControlMsg(SMSSendingFailed(ssi.invitationId, msg)))
        )
      case UrlShortened(shortUrl) =>
        val content = {
          val url = {
            val template = findAgentSpecificConfig(SMS_OFFER_TEMPLATE_DEEPLINK_URL, Option(domainId), appConfig)
            replaceVariables(template, Map(TOKEN -> shortUrl))
          }

          val template = findAgentSpecificConfig(SMS_MSG_TEMPLATE_OFFER_CONN_MSG, Option(domainId), appConfig)
          replaceVariables(template, Map(APP_URL_LINK -> url, REQUESTER_NAME -> ssi.senderName))
        }

        SmsTools.sendTextToPhoneNumber(
          SmsInfo(ssi.phoneNo, content)
        )(
          agentActorContext.appConfig,
          agentActorContext.smsSvc,
          agentActorContext.remoteMsgSendingSvc
        ) map { result =>
          logger.info(s"Sent SMS invite to number: ${ssi.phoneNo} with content '${content}'. Result: $result")
          Option(ControlMsg(SMSSent(ssi.invitationId, ssi.inviteURL, shortUrl)))
        } recover {
          case he: HandledErrorException => Option(ControlMsg(SMSSendingFailed(ssi.invitationId, s"Exception: $he")))
          case e: Exception => Option(ControlMsg(SMSSendingFailed(ssi.invitationId, s"Unknown error: ${e}")))
        }
    }
  }

  def handleUpdateTheirDidDoc(stdd: SetupTheirDidDoc):Future[Option[ControlMsg]] = {
    //TODO: modify this to efficiently route to itself if this is pairwise actor itself
    updateTheirDidDoc(stdd)
    for {
      _   <- setRoute(stdd.myDID)
      ctlMsg  <-
        getAgencyVerKeyFut.map { agencyVerKey =>
          val myVerKey = getVerKeyReqViaCache(state.myDid_!)
          val routingKeys = Vector(myVerKey, agencyVerKey)
          Option(ControlMsg(TheirDidDocUpdated(state.myDid_!, myVerKey, routingKeys)))
        }
    } yield ctlMsg
  }

  def updateTheirDidDoc(udd: SetupTheirDidDoc): Unit = {
    val rcd = TheirProvisionalDidDocDetail(udd.theirDID.getOrElse(""), udd.theirVerKey, udd.theirServiceEndpoint, udd.theirRoutingKeys)
    val csu = ConnectionStatusUpdated(reqReceived=true, answerStatusCode=MSG_STATUS_ACCEPTED.statusCode, theirProvisionalDidDocDetail=Option(rcd))
    writeAndApply(csu)
  }

  def getInternalReqHelperData(implicit reqMsgContext: ReqMsgContext): InternalReqHelperData =
    InternalReqHelperData(reqMsgContext)

  def msgPackVersion(msgId: MsgId): MsgPackVersion =
    msgState.getMsgPayload(msgId).flatMap(_.msgPackVersion).getOrElse(MPV_MSG_PACK)

  /**
   * this function gets executed post successful actor recovery (meaning all events are applied to state)
   * the purpose of this function is to update any 'LegacyAuthorizedKey' to 'AuthorizedKey'
   */
  override def postSuccessfulActorRecovery(): Unit = {
    super.postSuccessfulActorRecovery()
    if (state.relationship.nonEmpty) {
      val updatedMyDidDoc = updatedDidDocWithMigratedAuthKeys(state.myDidDoc)
      val updatedTheirDidDoc = updatedDidDocWithMigratedAuthKeys(state.theirDidDoc)
      val updatedRel = state.relationship
        .update(
          _.myDidDoc.setIfDefined(updatedMyDidDoc),
          _.thoseDidDocs.setIfDefined(updatedTheirDidDoc.map(Seq(_)))
        )
      state.updateRelationship(updatedRel)
    }
  }

  def agentConfigs: Map[String, AgentConfig] = state.configs

  def agencyDIDOpt: Option[DID] = state.agencyDID

  def ownerDID: Option[DID] = state.mySelfRelDID
  def ownerAgentKeyDID: Option[DID] = state.ownerAgentKeyDID

  def mySelfRelDIDReq: DID = domainId
  def myPairwiseVerKey: VerKey = getVerKeyReqViaCache(state.myDid_!)

  lazy val scheduledJobInitialDelay: Int = appConfig.getConfigIntOption(
    USER_AGENT_PAIRWISE_ACTOR_SCHEDULED_JOB_INITIAL_DELAY_IN_SECONDS).getOrElse(60)

  lazy val scheduledJobInterval: Int = appConfig.getConfigIntOption(
    USER_AGENT_PAIRWISE_ACTOR_SCHEDULED_JOB_INTERVAL_IN_SECONDS).getOrElse(300)

  /**
   * there are different types of actors (agency agent, agency pairwise, user agent and user agent pairwise)
   * when we store the persistence detail, we store these unique id for each of them
   * which then used during routing to know which type of region actor to be used to route the message
   *
   * @return
   */
  override def actorTypeId: Int = ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR

}

case class GetConfigDetail(name: String, req: Boolean = true)

//cmd

//internal cmd

case class ValidateAndProcessSendRemoteMsg(
                                            sendRemoteMsg: SendRemoteMsg,
                                            reqHelperData: InternalReqHelperData)

case class PersistAndProcessSendRemoteMsg(
                                           sendRemoteMsg: SendRemoteMsg,
                                           reqHelperData: InternalReqHelperData)

case class ProcessPersistedSendRemoteMsg(
                                          sendRemoteMsg: SendRemoteMsg,
                                          msgCreated: MsgCreated,
                                          reqHelperData: InternalReqHelperData) extends ActorMessageClass

case class StoreThreadContext(pinstId: PinstId, threadContext: ThreadContextDetail) extends ActorMessageClass
case class AddTheirDidDoc(theirDIDDoc: LegacyDIDDoc) extends ActorMessageClass
case class SetSponsorRel(rel: SponsorRel) extends ActorMessageClass
object SetSponsorRel {
  def apply(rel: Option[SponsorRel]): SetSponsorRel =
    new SetSponsorRel(rel.getOrElse(SponsorRel.empty))
}
