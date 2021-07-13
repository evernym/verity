package com.evernym.verity.actor.agent.user

import akka.actor.ActorRef
import akka.event.LoggingReceive
import akka.pattern.ask
import com.evernym.verity.{Exceptions, Status}
import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK}
import com.evernym.verity.actor.agent.msghandler.{AgentMsgProcessor, MsgRespConfig, ProcessTypedMsg, SendToProtocolActor}
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgParam}
import com.evernym.verity.actor.agent.msghandler.outgoing._
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.agent.msgsender.{AgentMsgSender, MsgDeliveryResult, SendMsgParam}
import com.evernym.verity.actor.agent.relationship.Tags.{CLOUD_AGENT_KEY, EDGE_AGENT_KEY, OWNER_AGENT_KEY}
import com.evernym.verity.actor.agent.relationship._
import com.evernym.verity.actor.agent.state._
import com.evernym.verity.actor.agent.state.base.AgentStatePairwiseImplBase
import com.evernym.verity.actor.agent.user.UserAgentPairwise.{COLLECTION_METRIC_MND_MSGS_DELIVRY_STATUS_TAG, COLLECTION_METRIC_MND_MSGS_DETAILS_TAG, COLLECTION_METRIC_MND_MSGS_PAYLOADS_TAG, COLLECTION_METRIC_MND_MSGS_TAG}
import com.evernym.verity.actor.agent.user.msgstore.{FailedMsgTracker, RetryEligibilityCriteria}
import com.evernym.verity.actor.agent.{SetupCreateKeyEndpoint, _}
import com.evernym.verity.actor.metrics.{RemoveCollectionMetric, UpdateCollectionMetric}
import com.evernym.verity.actor.msg_tracer.progress_tracker.MsgEvent
import com.evernym.verity.actor.persistence.InternalReqHelperData
import com.evernym.verity.actor.resourceusagethrottling.RESOURCE_TYPE_MESSAGE
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgWrapper}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.ConfigUtil.findAgentSpecificConfig
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.msg_tracer.MsgTraceProvider._
import com.evernym.verity.protocol.container.actor.{FromProtocol, UpdateMsgDeliveryStatus}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{DID, Parameter, VerKey, _}
import com.evernym.verity.protocol.protocols._
import com.evernym.verity.protocol.protocols.connecting.common._
import com.evernym.verity.protocol.protocols.connecting.v_0_5.{ConnectingMsgFamily => ConnectingMsgFamily_0_5}
import com.evernym.verity.protocol.protocols.connecting.v_0_6.{ConnectingMsgFamily => ConnectingMsgFamily_0_6}
import com.evernym.verity.protocol.protocols.connections.v_1_0.Ctl.TheirDidDocUpdated
import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.{SetupTheirDidDoc, UpdateTheirDid}
import com.evernym.verity.protocol.protocols.connections.v_1_0.{ConnectionsMsgFamily, Ctl}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Signal.MoveProtocol
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.{SMSSendingFailed, SMSSent}
import com.evernym.verity.texter.SmsInfo
import com.evernym.verity.urlshortener.{DefaultURLShortener, UrlInfo, UrlShortened, UrlShorteningFailed}
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.util.Util.replaceVariables
import com.evernym.verity.util._
import com.evernym.verity.vault._
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.metrics.{InternalSpan, MetricsWriterExtension, MetricsWriter}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.SendSMSInvite
import org.json.JSONObject

import scala.concurrent.Future
import scala.util.{Failure, Left, Success, Try}

/**
 Represents the part of a user agent that's dedicated to a single pairwise
 relationship.
 */
class UserAgentPairwise(val agentActorContext: AgentActorContext, val metricsActorRef: ActorRef)
  extends UserAgentCommon
    with UserAgentPairwiseStateUpdateImpl
    with AgentMsgSender
    with UsesConfigs
    with PairwiseConnState
    with MsgDeliveryResultHandler
    with MsgNotifierForUserAgentPairwise
    with FailedMsgRetrier
    with AgentSnapshotter[UserAgentPairwiseState] {

  type StateType = UserAgentPairwiseState
  var state = new UserAgentPairwiseState

  override val metricsWriter: MetricsWriter = MetricsWriterExtension(context.system).get()

  override lazy val failedMsgTracker: Option[FailedMsgTracker] = Option(
    new FailedMsgTracker(maxRetryCount, retryEligibilityCriteriaProvider)
  )

  override final def receiveAgentCmd: Receive = commonCmdReceiver orElse cmdReceiver orElse retryCmdReceiver

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
  }

  /**
   * internal command handlers
   */
  val cmdReceiver: Receive = LoggingReceive.withLabel("cmdReceiver") {
    case saw: SetAgentActorDetail                           => setAgentActorDetail(saw)
    case _: SetupCreateKeyEndpoint if state.myDid.isDefined => throw new BadRequestErrorException(AGENT_ALREADY_CREATED.statusCode)
    case cke: SetupCreateKeyEndpoint                        => handleCreateKeyEndpoint(cke)
    case ppgm: ProcessPersistedSendRemoteMsg                => processPersistedSendRemoteMsg(ppgm)
    case mss: MsgSentSuccessfully                           => handleMsgSentSuccessfully(mss)
    case msf: MsgSendingFailed                              => handleMsgSendingFailed(msf)
  }

  override final def receiveAgentEvent: Receive =
    //dhh I don't understand this orElse syntax
    commonEventReceiver orElse
      eventReceiver orElse
      pairwiseConnEventReceiver orElse
      msgEventReceiver orElse
      legacyEventReceiver orElse
      agentSpecificEventReceiver

  val eventReceiver: Receive = {
    case os: OwnerSetForAgent     =>
      state = state
        .withMySelfRelDID(os.ownerDID)
        .withOwnerAgentDidPair(DidPair(os.agentDID, os.agentDIDVerKey))
      updateStateWithOwnerAgentKey()
    case ads: AgentDetailSet      =>
      handleAgentDetailSet(ads)
    case csu: ConnStatusUpdated   =>
      state = state.withConnectionStatus(ConnectionStatus(answerStatusCode = csu.statusCode))
    case pis: PublicIdentityStored =>
      state = state.withPublicIdentity(DidPair(pis.DID, pis.verKey))
  }

  //this is for backward compatibility
  val legacyEventReceiver: Receive = {

    //includes details of 'their' edge pairwise DID and 'their' cloud agent DID
    case tads: TheirAgentDetailSet =>
      val theirDidDoc =
        DidDocBuilder()
          .withDid(tads.DID)
          .withAuthKey(tads.DID, "")
          .withAuthKey(tads.agentKeyDID, "", Set(CLOUD_AGENT_KEY))
          .didDoc
      state = state
        .relationship
        .map { r =>
          state.withRelationship(r.update(_.thoseDidDocs.setIfDefined(Option(Seq(theirDidDoc)))))
        }
        .getOrElse(state)
      state = state.withConnectionStatus(ConnectionStatus(reqReceived=true, MSG_STATUS_ACCEPTED.statusCode))


    //includes details of 'their' cloud agent (cloud agent DID, cloud agent ver key key and delegation proof signature)
    case takdps: TheirAgentKeyDlgProofSet =>
      val lrd = LegacyRoutingDetail("", agentKeyDID = takdps.DID, agentVerKey = takdps.delegatedKey, takdps.signature)
      updateLegacyRelationshipState(state.theirDid_!, state.theirDidAuthKey.flatMap(_.verKeyOpt).getOrElse(""), lrd)

    //includes details of 'their' agency
    case tais: TheirAgencyIdentitySet =>
      //TODO This can be more easily done by teaching the LegacyRoutingServiceEndpoint and RoutingServiceEndpoint how to do the conversion.
      val updatedDidDoc = state.relationship.flatMap(_.theirDidDoc.map { tdd =>
        val updatedEndpointSeq: Seq[EndpointADT] = tdd.endpoints_!.endpoints.map(_.endpointADTX).map {
          case lep: LegacyRoutingServiceEndpoint => lep.copy(agencyDID = tais.DID)
          case ep: RoutingServiceEndpoint        => ep
          case _                                 => throw new MatchError("unsupported endpoint matched")
        }.map(EndpointADT.apply)
        val updatedEndpoints = Endpoints(updatedEndpointSeq)
        tdd.update(_.endpoints := updatedEndpoints)
      })
      state = state
        .relationship
        .map { r =>
          state.withRelationship(r.update(_.thoseDidDocs.setIfDefined(updatedDidDoc.map(Seq(_)))))
        }
        .getOrElse(state)
      state = state.withConnectionStatus(ConnectionStatus(reqReceived=true, MSG_STATUS_ACCEPTED.statusCode))
  }

  //TODO: not sure why we have this, we may wanna test and remove this if not needed
  val agentSpecificEventReceiver: Receive = {
    case _ =>
  }

  def encParamFromThisAgentToOwner: EncryptParam =
    encParamBuilder
      .withRecipDID(mySelfRelDIDReq)
      .withSenderVerKey(state.thisAgentVerKeyReq)
      .encryptParam

  def checkIfTheirDidDocExists(): Unit = {
    if (state.theirDidDoc.isEmpty)
      throw new BadRequestErrorException(CONNECTION_DOES_NOT_EXIST.statusCode, Option("connection not yet established/completed"))
  }

  def checkMsgSenderIfConnectionIsNotYetEstablished(msgSenderVerKey: VerKey): Unit = {
    if (state.theirDidDoc.isEmpty)
      AgentMsgProcessor.checkIfMsgSentByAuthedMsgSenders(allAuthedKeys, msgSenderVerKey)
  }

  def handleAgentDetailSet(ad: AgentDetailSet): Unit = {
    state = state.withThisAgentKeyId(ad.agentKeyDID)
    val isThisAnEdgeAgent = ad.forDID == ad.agentKeyDID
    val agentKeyTags: Set[Tags] = if (isThisAnEdgeAgent) Set(EDGE_AGENT_KEY) else Set(CLOUD_AGENT_KEY)
    val myDidDoc =
      DidDocBuilder()
        .withDid(ad.forDID)
        .withAuthKey(ad.forDID, ad.forDIDVerKey, Set(EDGE_AGENT_KEY))
        .withAuthKey(ad.agentKeyDID, ad.agentKeyDIDVerKey, agentKeyTags)
        .didDoc
    state = state.withRelationship(PairwiseRelationship("pairwise", Option(myDidDoc)))
    updateStateWithOwnerAgentKey()
  }

  def updateStateWithOwnerAgentKey(): Unit = {
    state.ownerAgentDidPair.foreach { didPair =>
      val authKey = state.myAuthKeys.find(_.keyId == didPair.DID)
      authKey match {
        case None =>
          state = state.copy(relationship = state.relWithNewAuthKeyAddedInMyDidDoc(
            didPair.DID, didPair.verKey, Set(OWNER_AGENT_KEY)))

        case Some(ak) if ak.verKeyOpt.isDefined =>
          state = state.copy(ownerAgentDidPair = Option(didPair.copy(verKey = ak.verKey)))

        case _ => //nothing to do
      }
    }
  }

  def authedMsgSenderVerKeys: Set[VerKey] = state.allAuthedVerKeys

  def retryEligibilityCriteriaProvider(): RetryEligibilityCriteria = {
    (state.myDid, state.theirDidDoc.isDefined) match {
      case (Some(myPairwiseDID), true) =>
        RetryEligibilityCriteria(senderDID = Option(myPairwiseDID),
          Set(CREATE_MSG_TYPE_CONN_REQ, CREATE_MSG_TYPE_CONN_REQ_ANSWER),
          Set(theirRoutingTarget))
      case _                           => RetryEligibilityCriteria()
    }
  }

  override def msgSentSuccessfully(mss: MsgSentSuccessfully): Unit = {
    self ! mss
  }

  override def msgSendingFailed(msf: MsgSendingFailed): Unit = {
    self ! msf
  }

  def handleMsgSentSuccessfully(mss: MsgSentSuccessfully): Unit = {
    notifyUserForSuccessfulMsgDelivery(NotifyMsgDetail(mss.uid, mss.typ))
  }

  def handleMsgSendingFailed(msf: MsgSendingFailed): Unit = {
    notifyUserForFailedMsgDelivery(NotifyMsgDetail(msf.uid, msf.typ))
  }

  def stateDetailsFor: Future[ProtoRef => PartialFunction[String, Parameter]] = {
    val getConnectEndpointDetail: String = {
      val msg = ActorEndpointDetail(userAgentPairwiseRegionName, entityId)
      DefaultMsgCodec.toJson(msg)
    }
    for (
      agencyDidPair   <- agencyDidPairFut();
      filteredConfigs <- getConfigs(Set(NAME_KEY, LOGO_URL_KEY))
    ) yield {
      p: ProtoRef => {
        case SELF_ID                                => Parameter(SELF_ID, state.myDid_!)
        case OTHER_ID                               => Parameter(OTHER_ID, state.theirDid.getOrElse(UNKNOWN_OTHER_ID))
        case AGENCY_DID                             => Parameter(AGENCY_DID, agencyDIDReq)
        case AGENCY_DID_VER_KEY                     => Parameter(AGENCY_DID_VER_KEY, agencyDidPair.verKey)
        case MY_SELF_REL_DID                        => Parameter(MY_SELF_REL_DID, mySelfRelDIDReq)
        case MY_PAIRWISE_DID                        => Parameter(MY_PAIRWISE_DID, state.myDid_!)
        case MY_PAIRWISE_DID_VER_KEY                => Parameter(MY_PAIRWISE_DID_VER_KEY, myPairwiseVerKey)
        case THEIR_PAIRWISE_DID                     => Parameter(THEIR_PAIRWISE_DID, state.theirDid.getOrElse(""))
        case MY_PUBLIC_DID                          => Parameter(MY_PUBLIC_DID, state.publicIdentity.map(_.DID).getOrElse(""))

        case THIS_AGENT_VER_KEY                     => Parameter(THIS_AGENT_VER_KEY, state.thisAgentVerKeyReq)
        case THIS_AGENT_WALLET_ID                   => Parameter(THIS_AGENT_WALLET_ID, agentWalletIdReq)

        case NAME                                   => Parameter(NAME, agentName(filteredConfigs.configs))
        case LOGO_URL                               => Parameter(LOGO_URL, agentLogoUrl(filteredConfigs.configs))
        case CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON  => Parameter(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON, getConnectEndpointDetail)

        case DEFAULT_ENDORSER_DID                   => Parameter(DEFAULT_ENDORSER_DID, defaultEndorserDid)

        case DATA_RETENTION_POLICY                  => Parameter(DATA_RETENTION_POLICY, ConfigUtil.getRetentionPolicy(appConfig, domainId, p.msgFamilyName).configString)
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
    encParamBasedOnMsgSender(reqMsgContext.latestMsgSenderVerKey)
  }

  override def postUpdateConfig(updateConf: UpdateConfigReqMsg, senderVerKey: Option[VerKey]): Unit = {
    val configName = expiryTimeInSecondConfigNameForMsgType(CREATE_MSG_TYPE_CONN_REQ)

    updateConf.configs.filter(_.name == configName).foreach { c =>
      val msgs = Set(
        UpdateMsgExpirationTime_MFV_0_5(CREATE_MSG_TYPE_CONN_REQ, c.value.toInt),
        UpdateMsgExpirationTime_MFV_0_6(CREATE_MSG_TYPE_CONN_REQ, c.value.toInt))
      msgs.foreach { msg =>
        sendToAgentMsgProcessor(ProcessTypedMsg(msg.typedMsg, relationshipId, DEFAULT_THREAD_ID,
          senderParticipantId(senderVerKey), Option(MsgRespConfig(isSyncReq = true)), None, None))
      }
    }
  }

  def handleCreateMsgGeneral(amw: AgentMsgWrapper)(implicit reqMsgContext: ReqMsgContext): Unit = {
    metricsWriter.runWithSpan("handleCreateMsgGeneral", "UserAgentPairwise", InternalSpan) {
      val createMsgReq = amw.headAgentMsg.convertTo[CreateMsgReqMsg_MFV_0_5]
      val userId = userIdForResourceUsageTracking(amw.senderVerKey)
      val resourceName = ResourceUsageUtil.getCreateMsgReqMsgName(createMsgReq.mtype)
      addUserResourceUsage(RESOURCE_TYPE_MESSAGE, resourceName, reqMsgContext.clientIpAddressReq, userId)
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
    metricsWriter.runWithSpan("handleSendRemoteMsg", "UserAgentPairwise", InternalSpan) {
      recordInMsgEvent(reqMsgContext.id, MsgEvent(sendRemoteMsg.id, sendRemoteMsg.mtype))
      validateAndProcessSendRemoteMsg(ValidateAndProcessSendRemoteMsg(sendRemoteMsg, getInternalReqHelperData))
    }
  }

  def buildMsgAnsweredEvt(uid: MsgId, newStatusCode: String, refMsgId: Option[String]=None): MsgAnswered = {
    MsgAnswered(uid, newStatusCode, refMsgId.orNull, getMillisForCurrentUTCZonedDateTime)
  }

  def buildMsgDetail(uid: MsgId, name: String, valueOpt: Option[String]): Option[Any] = {
    valueOpt.map(value => MsgDetailAdded(uid, name, value))
  }

  def buildSendRemoteMsgDetailEvents(uid: MsgId, sendRemoteMsg: SendRemoteMsg): List[Any] = {
    val titleEvent = buildMsgDetail(uid, TITLE, sendRemoteMsg.title)
    val detailEvent = buildMsgDetail(uid, DETAIL, sendRemoteMsg.detail)
    val nameEvent = buildMsgDetail(uid, NAME_KEY, sendRemoteMsg.senderName)
    val logoUrlEvent = buildMsgDetail(uid, LOGO_URL_KEY, sendRemoteMsg.senderLogoUrl)
    (titleEvent ++ detailEvent ++ nameEvent ++ logoUrlEvent).toList
  }

  def processPersistedSendRemoteMsg(ppsrm: ProcessPersistedSendRemoteMsg): Unit = {
    metricsWriter.runWithSpan("processPersistedSendRemoteMsg", "UserAgentPairwise", InternalSpan) {
      recordOutMsgEvent(ppsrm.reqHelperData.reqMsgContext.id,
        MsgEvent(ppsrm.msgCreated.uid, ppsrm.msgCreated.typ,
          OptionUtil.emptyOption(ppsrm.msgCreated.refMsgId).map(rmid => s"refMsgId: $rmid")))
      implicit val reqMsgContext: ReqMsgContext = ppsrm.reqHelperData.reqMsgContext
      val msgCreatedResp = SendRemoteMsgHelper.buildRespMsg(ppsrm.msgCreated.uid)(reqMsgContext.agentMsgContext)
      val otherRespMsgs = if (ppsrm.sendRemoteMsg.sendMsg) sendMsgV1(List(ppsrm.msgCreated.uid)) else List.empty

      val param = AgentMsgPackagingUtil.buildPackMsgParam(getEncParamBasedOnMsgSender, msgCreatedResp ++ otherRespMsgs, reqMsgContext.wrapInBundledMsg)
      logger.debug("param (during general proof/cred msgs): " + param)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormatReq, param)(agentMsgTransformer, wap, metricsWriter)
      sendRespMsg("SendRemoteMsgResp", rp, sender)
    }
  }

  lazy val useAsyncPersistForMsgForward: Boolean =
    appConfig.getBooleanOption(PERSISTENCE_USE_ASYNC_MSG_FORWARD).getOrElse(false)

  def persistAndProcessSendRemoteMsg(papsrm: PersistAndProcessSendRemoteMsg): Unit = {
    metricsWriter.runWithSpan("persistAndProcessSendRemoteMsg", "UserAgentPairwise", InternalSpan) {
      implicit val reqMsgContext: ReqMsgContext = papsrm.reqHelperData.reqMsgContext

      val senderDID = getSenderDIDBySenderVerKey(reqMsgContext.latestMsgSenderVerKeyReq)

      val payloadParam = StorePayloadParam(papsrm.sendRemoteMsg.`@msg`, None)
      val msgStoredEvents = buildMsgStoredEventsV1(papsrm.sendRemoteMsg.id, papsrm.sendRemoteMsg.mtype,
        state.myDid_!, senderDID, papsrm.sendRemoteMsg.sendMsg, papsrm.sendRemoteMsg.threadOpt, None, Option(payloadParam))

      val msgDetailEvents = buildSendRemoteMsgDetailEvents(msgStoredEvents.msgCreatedEvent.uid, papsrm.sendRemoteMsg)

      val msgStatusUpdatedEvt = papsrm.sendRemoteMsg.replyToMsgId.map { replyToMsgId =>
        buildMsgAnsweredEvt(replyToMsgId, MSG_STATUS_ACCEPTED.statusCode, Option(msgStoredEvents.msgCreatedEvent.uid))
      }
      val eventsToPersists = msgStoredEvents.allEvents ++ msgDetailEvents ++ msgStatusUpdatedEvt
      if (useAsyncPersistForMsgForward) {
        asyncWriteAndApplyAll(eventsToPersists)
      } else {
        writeAndApplyAll(eventsToPersists)
      }
      self tell(ProcessPersistedSendRemoteMsg(papsrm.sendRemoteMsg, msgStoredEvents.msgCreatedEvent, getInternalReqHelperData), sender())
    }
  }

  def validateAndProcessSendRemoteMsg(vapsrm: ValidateAndProcessSendRemoteMsg): Unit = {
    metricsWriter.runWithSpan("validateAndProcessSendRemoteMsg", "UserAgentPairwise", InternalSpan) {
      implicit val reqMsgContext: ReqMsgContext = vapsrm.reqHelperData.reqMsgContext
      checkIfTheirDidDocExists()
      checkMsgSenderIfConnectionIsNotYetEstablished(reqMsgContext.latestMsgSenderVerKeyReq)
      checkIfMsgExists(vapsrm.sendRemoteMsg.replyToMsgId)
      vapsrm.sendRemoteMsg.replyToMsgId.foreach(state.checkIfMsgAlreadyNotInAnsweredState)
      persistAndProcessSendRemoteMsg(PersistAndProcessSendRemoteMsg(vapsrm.sendRemoteMsg, getInternalReqHelperData))
    }
  }

  def handleSendMsgs(sendMsgReq: SendMsgsReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    val userId = userIdForResourceUsageTracking(reqMsgContext.latestMsgSenderVerKey)
    val resourceName = ResourceUsageUtil.getMessageResourceName(sendMsgReq.msgFamilyDetail)
    addUserResourceUsage(RESOURCE_TYPE_MESSAGE, resourceName, reqMsgContext.clientIpAddressReq, userId)
    val msgSentRespMsg = sendMsgV1(sendMsgReq.uids.map(uid => uid))
    val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, msgSentRespMsg, reqMsgContext.wrapInBundledMsg)
    val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormatReq, param)(agentMsgTransformer, wap, metricsWriter)
    sendRespMsg("SendMsgResp", rp, sender)
  }

  def logErrorsIfFutureFails(f: Future[Any], op: String): Unit = {
    f.recover {
      case e: Exception =>
        logger.error("operation execution failed", ("operation", op), (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
    }
  }

  def sendMsgV1(uids: List[String])(implicit reqMsgContext: ReqMsgContext): List[Any] = {
    metricsWriter.runWithSpan("sendMsgV1", "UserAgentPairwise", InternalSpan) {
      uids.foreach { uid =>
        val msg = getMsgReq(uid)
        logErrorsIfFutureFails(Future(sendGeneralMsg(uid)),
          s"send messages to their agent [uid = $uid, type= ${msg.getType}]")
      }
      SendMsgsMsgHelper.buildRespMsg(uids)(reqMsgContext.agentMsgContext)
    }
  }

  def sendGeneralMsg(uid: MsgId)
                    (implicit reqMsgContext: ReqMsgContext): Unit = {
    metricsWriter.runWithSpan("sendGeneralMsg", "UserAgentPairwise", InternalSpan) {
      getMsgOpt(uid).foreach { msg =>
        val sentBySelf = msg.senderDID == state.myDid_!
        if (sentBySelf) {
          self ! UpdateMsgDeliveryStatus(uid,
            theirRoutingTarget, MSG_DELIVERY_STATUS_PENDING.statusCode, None)
          sendMsgToTheirAgent(uid, isItARetryAttempt = false, reqMsgContext.agentMsgContext.msgPackFormat)
        } else {
          sendToMyRegisteredComMethods(uid)
        }
        val nextHop = if (sentBySelf) NEXT_HOP_THEIR_ROUTING_SERVICE else NEXT_HOP_MY_EDGE_AGENT
        MsgRespTimeTracker.recordMetrics(reqMsgContext.id, msg.getType, nextHop)
      }
    }
  }

  def buildLegacySendRemoteMsg_MFV_0_5(uid: MsgId, fc: AgentConfigs): List[Any] = {
    metricsWriter.runWithSpan("buildLegacySendRemoteMsg_MFV_0_5", "UserAgentPairwise", InternalSpan) {
      val msg = getMsgReq(uid)
      val replyToMsgId = msgStore.getReplyToMsgId(uid)
      val mds = getMsgDetails(uid)
      val payloadWrapper = getMsgPayloadReq(uid)
      buildLegacySendRemoteMsg_MFV_0_5(uid, msg.getType, payloadWrapper.msg, replyToMsgId, mds, msg.thread, fc)
    }
  }

  def buildLegacySendRemoteMsg_MFV_0_5(msgId: MsgId,
                                       msgType: String,
                                       payload: Array[Byte],
                                       replyToMsgId: Option[MsgId],
                                       msgDetail: Map[AttrName, AttrValue]=Map.empty,
                                       threadOpt: Option[Thread]=None,
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
    metricsWriter.runWithSpan("buildSendRemoteMsg_MFV_0_6", "UserAgentPairwise", InternalSpan) {
      val msg = getMsgReq(uid)
      val replyToMsgId = msgStore.getReplyToMsgId(uid)
      val mds = getMsgDetails(uid)
      val title = mds.get(TITLE)
      val detail = mds.get(DETAIL)
      val payloadMsg = getMsgPayloadReq(uid).msg
      val nativeMsg = SendRemoteMsgReq_MFV_0_6(MSG_TYPE_DETAIL_SEND_REMOTE_MSG, uid,
        msg.getType, new JSONObject(new String(payloadMsg)), sendMsg=msg.sendMsg, msg.thread, title, detail, replyToMsgId)
      List(nativeMsg)
    }
  }

  private def buildSendMsgParam(uid: MsgId, msgType: String, msg: Array[Byte], isItARetryAttempt: Boolean): SendMsgParam = {
    SendMsgParam(uid, msgType, msg, agencyDIDReq, theirRoutingParam, isItARetryAttempt)
  }

  //NOTE: this is called from FailedMsgRetrier
  def sendMsgToTheirAgent(uid: String, isItARetryAttempt: Boolean): Future[Any] = {
    val msgPackFormat = getMsgPayload(uid).flatMap(_.msgPackFormat).getOrElse(MPF_MSG_PACK)
    sendMsgToTheirAgent(uid, isItARetryAttempt, msgPackFormat)
  }

  //target msg needs to be prepared/packed
  def sendMsgToTheirAgent(uid: MsgId, isItARetryAttempt: Boolean, mpf: MsgPackFormat): Future[Any] = {
    val msg = getMsgReq(uid)
    val payload = getMsgPayload(uid)
    logger.debug("msg building started", (LOG_KEY_UID, uid))
    getAgentConfigs(Set(GetConfigDetail(NAME_KEY, req = false), GetConfigDetail(LOGO_URL_KEY, req = false))).flatMap { fc: AgentConfigs =>
      logger.debug("got required configs", (LOG_KEY_UID, uid))
      (theirRoutingDetail, payload) match {
        case (Some(Left(_: LegacyRoutingDetail)), _) =>
          val agentMsgs = mpf match {
            case MPF_MSG_PACK   => buildLegacySendRemoteMsg_MFV_0_5(uid, fc)
            case MPF_INDY_PACK  => buildSendRemoteMsg_MFV_0_6(uid)
            case x              => throw new RuntimeException("unsupported msg pack format: " + x)
          }
          buildAndSendMsgToTheirRoutingService(uid, msg.getType, agentMsgs, mpf)
        case (Some(Right(_: RoutingDetail)), Some(p)) =>
          buildRoutedPackedMsgForTheirRoutingService(mpf, p.msg, msg.`type`, metricsWriter).map { pm =>
            val smp = buildSendMsgParam(uid, msg.getType, pm.msg, isItARetryAttempt = false)
            sendFinalPackedMsgToTheirRoutingService(pm, smp)
          }
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
        val sm = buildSendMsgParam(uid, msg.`type`, null, isItARetryAttempt = isItARetryAttempt)
        handleMsgDeliveryResult(MsgDeliveryResult.failed(sm, MSG_DELIVERY_STATUS_FAILED, Exceptions.getErrorMsg(e)))
    }
  }

  //target msg is already packed and it needs to be packed inside general msg wrapper
  def buildAndSendGeneralMsgToTheirRoutingService(smp: SendMsgParam,
                                                  thread: Option[Thread]=None): Future[Any] = {
    val packedMsgFut = if (smp.theirRoutingParam.route.isLeft) {
      val agentMsgs = buildLegacySendRemoteMsg_MFV_0_5(smp.uid, smp.msgType, smp.msg, None, Map.empty, thread)
      buildReqMsgForTheirRoutingService(MPF_MSG_PACK, agentMsgs, wrapInBundledMsgs = true, smp.msgType, metricsWriter)
    } else {
      buildRoutedPackedMsgForTheirRoutingService(MPF_INDY_PACK, smp.msg, smp.msgType, metricsWriter)
    }
    packedMsgFut.map { pm =>
      sendFinalPackedMsgToTheirRoutingService(pm, smp.copy(msg = pm.msg))
    }
  }

  //final target msg is ready and needs to be packed as per their routing service
  def buildAndSendMsgToTheirRoutingService(uid: MsgId,
                                           msgType: String,
                                           agentMsgs: List[Any],
                                           msgPackFormat: MsgPackFormat): Future[Any] = {
    metricsWriter.runWithSpan("buildAndSendMsgToTheirRoutingService", "UserAgentPairwise", InternalSpan) {
      val packedMsgFut = buildReqMsgForTheirRoutingService(msgPackFormat, agentMsgs, wrapInBundledMsgs = true, msgType, metricsWriter)
      logger.debug("agency msg prepared", (LOG_KEY_UID, uid))
      packedMsgFut.map { pm =>
        val smp = buildSendMsgParam(uid, msgType, pm.msg, isItARetryAttempt = false)
        sendFinalPackedMsgToTheirRoutingService(pm, smp)
      }
    }
  }

  private def sendFinalPackedMsgToTheirRoutingService(packedMsg: PackedMsg,
                                                      smp: SendMsgParam): Future[Any] = {
    sendToTheirAgencyEndpoint(smp.copy(msg = packedMsg.msg), metricsWriter).map {
      case Left(e) =>
        recordOutMsgDeliveryEvent(smp.uid, MsgEvent.withTypeAndDetail(smp.msgType,
          s"FAILED [outgoing message to their routing service (${smp.theirRoutingParam.routingTarget}) (error: ${e.toString})]"))
      case _ =>
        recordOutMsgDeliveryEvent(smp.uid, MsgEvent.withTypeAndDetail(smp.msgType,
          s"SENT [outgoing message to their routing service (${smp.theirRoutingParam.routingTarget})]"))
    }.recover {
      case e: Throwable =>
        recordOutMsgDeliveryEvent(smp.uid, MsgEvent.withTypeAndDetail(smp.msgType,
          s"FAILED [outgoing message to their routing service (${smp.theirRoutingParam.routingTarget}) (error: ${e.getMessage})]"))
    }
  }

  private def sendToMyRegisteredComMethods(uid: MsgId): Future[Any] = {
    if (! state.isConnectionStatusEqualTo(CONN_STATUS_DELETED.statusCode)) {
      val msg = getMsgReq(uid)
      val payloadWrapper = getMsgPayload(uid)
      notifyUserForNewMsg(NotifyMsgDetail(uid, msg.getType, payloadWrapper))
    } else {
      val errorMsg = s"connection is marked as DELETED, user won't be notified about this msg: $uid"
      logger.warn("user agent pairwise", "notify user",
        s"connection is marked as DELETED, user won't be notified about this msg", uid)
      Future.failed(new RuntimeException(errorMsg))
    }
  }

  def handleUpdateConnStatusMsg(updateConnStatus: UpdateConnStatusReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    val userId = userIdForResourceUsageTracking(reqMsgContext.latestMsgSenderVerKey)
    val resourceName = ResourceUsageUtil.getMessageResourceName(updateConnStatus.msgFamilyDetail)
    addUserResourceUsage(RESOURCE_TYPE_MESSAGE, resourceName, reqMsgContext.clientIpAddressReq, userId)
    if (updateConnStatus.statusCode != CONN_STATUS_DELETED.statusCode) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option(s"invalid status code value: ${updateConnStatus.statusCode}"))
    }
    writeAndApply(ConnStatusUpdated(updateConnStatus.statusCode))
    val connectionStatusUpdatedRespMsg = UpdateConnStatusMsgHelper.buildRespMsg(updateConnStatus.statusCode)(reqMsgContext.agentMsgContext)
    val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, connectionStatusUpdatedRespMsg, reqMsgContext.wrapInBundledMsg)
    val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormatReq, param)(agentMsgTransformer, wap, metricsWriter)
    sendRespMsg("ConnStatusUpdatedResp", rp)
  }

  val allowedUnAuthedLegacyConnectingMsgNames: Set[String] =
    Set(
        CREATE_MSG_TYPE_CONN_REQ_ANSWER,
        MSG_TYPE_CONN_REQ_ACCEPTED, MSG_TYPE_CONN_REQ_DECLINED,
        CREATE_MSG_TYPE_CONN_REQ_REDIRECTED, MSG_TYPE_CONN_REQ_REDIRECTED)

  val allowedUnAuthedConnectionsMsgNames: Set[String] = Set("request")

  override def allowedUnAuthedMsgTypes: Set[MsgType] = if (state.theirDidDoc.isEmpty) {
    allowedUnAuthedLegacyConnectingMsgNames.map(ConnectingMsgFamily_0_5.msgType) ++
      allowedUnAuthedLegacyConnectingMsgNames.map(ConnectingMsgFamily_0_6.msgType) ++
      allowedUnAuthedConnectionsMsgNames.map(ConnectionsMsgFamily.msgType)
  } else Set.empty

  def handleCreateKeyEndpoint(scke: SetupCreateKeyEndpoint): Unit = {
    val pidEvent = scke.pid.map(pd => ProtocolIdDetailSet(pd.protoRef.msgFamilyName, pd.protoRef.msgFamilyVersion, pd.pinstId))
    scke.ownerAgentActorEntityId.foreach(setAgentWalletId)
    val pubIdEvent = scke.publicIdentity.map{ pi => PublicIdentityStored(pi.DID, pi.verKey) }
    val odsEvt = OwnerSetForAgent(scke.mySelfRelDID, scke.ownerAgentKeyDIDReq, scke.ownerAgentKeyDIDVerKeyReq)
    val cdsEvt = AgentDetailSet(
      scke.forDIDPair.DID, scke.newAgentKeyDIDPair.DID,
      scke.forDIDPair.verKey, scke.newAgentKeyDIDPair.verKey
    )
    val eventsToPersist: List[Any] = (pidEvent ++ pubIdEvent ++ List(odsEvt, cdsEvt)).toList
    writeAndApplyAll(eventsToPersist)
    val sndr = sender()

    val setRouteFut = setRoute(scke.forDIDPair.DID, Option(scke.newAgentKeyDIDPair.DID))
    val updateUserAgentFut = scke.ownerAgentKeyDIDPair match {
      case Some(dp) => agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(dp.DID, cdsEvt))
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
    val edgeVerKey = state.myDidAuthKey.flatMap(_.verKeyOpt)
    val otherEntityEdgeVerKey = state.theirDidAuthKey.flatMap(_.verKeyOpt)

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
    senderParticipantId
  }

  override def sendMsgToTheirDomain(omp: OutgoingMsgParam,
                                    msgId: MsgId,
                                    msgName: MsgName,
                                    thread: Option[Thread]=None): Unit = {
    logger.debug("about to send msg to other entity: " + msgId)
    omp.givenMsg match {
      case pm: PackedMsg =>
        val sendMsgParam: SendMsgParam = buildSendMsgParam(msgId, msgName, pm.msg, isItARetryAttempt=false)
        buildAndSendGeneralMsgToTheirRoutingService(sendMsgParam, thread)
      case _: JsonMsg =>
        //between cloud agents, we don't support sending json messages
        logger.error("sending json messages to other domain not supported")
    }
  }

  def futureNone: Future[None.type] = Future.successful(None)

  override def handleSpecificSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] = {
    //pinst (legacy connecting protocol) -> connecting actor driver -> this actor
    case SignalMsgParam(_: ConnReqReceived, _)                 => writeAndApply(ConnectionStatusUpdated(reqReceived = true)); futureNone
    //pinst (legacy connecting protocol) -> connecting actor driver -> this actor
    case SignalMsgParam(cc: ConnectionStatusUpdated, _)        => writeAndApply(cc); futureNone
    //pinst (legacy connecting protocol) -> connecting actor driver -> this actor
    case SignalMsgParam(nu: NotifyUserViaPushNotif, _)         => notifyUser(nu); futureNone
    //pinst (legacy connecting protocol) -> connecting actor driver -> this actor
    case SignalMsgParam(sm: SendMsgToRegisteredEndpoint, _)    => sendAgentMsgToRegisteredEndpoint(sm)
    //pinst (connections 1.0) -> connections actor driver -> this actor
    case SignalMsgParam(dc: SetupTheirDidDoc, _)               => handleUpdateTheirDidDoc(dc)
    //pinst (connections 1.0) -> connections actor driver -> this actor
    case SignalMsgParam(utd: UpdateTheirDid, _)                => handleUpdateTheirDid(utd)
    //pinst (relationship 1.0) -> relationship actor driver -> this actor
    case SignalMsgParam(ssi: SendSMSInvite, _)                 => handleSendingSMSInvite(ssi)
    case SignalMsgParam(si: MoveProtocol, _)                   => handleMoveProtocol(si)
  }

  def handleUpdateTheirDid(utd: UpdateTheirDid):Future[Option[ControlMsg]] = {
    writeAndApply(TheirDidUpdated(utd.theirDID))
    Future.successful(Option(ControlMsg(Ctl.TheirDidUpdated())))
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
          agentActorContext.msgSendingSvc
        ) map { result =>
          logger.info(s"Sent SMS invite to number: ${ssi.phoneNo} with content '$content'. Result: $result")
          Option(ControlMsg(SMSSent(ssi.invitationId, ssi.inviteURL, shortUrl)))
        } recover {
          case he: HandledErrorException => Option(ControlMsg(SMSSendingFailed(ssi.invitationId, s"Exception: $he")))
          case e: Exception => Option(ControlMsg(SMSSendingFailed(ssi.invitationId, s"Unknown error: $e")))
        }
    }
  }

  def handleMoveProtocol(signal: MoveProtocol): Future[Option[ControlMsg]] = {

    val protoEntry = Try(
      ProtoRef.fromString(signal.protoRefStr)
    )
    .map(protocolRegistry.find_!)

    val oldPinstId = protoEntry
      .map{ e =>
        resolvePinstId(
          e.protoDef,
          e.pinstIdResol,
          Some(signal.fromRelationship),
          signal.threadId,
          None
        )
      }

    val msg = for (
      p <- oldPinstId;
      r <- Try(state.relationship.getOrElse(throw new Exception("No relationship define for this actor")))
    ) yield FromProtocol(p, r)

    val toPinstPair = protoEntry
      .map{ e =>
        val pinst = resolvePinstId(
          e.protoDef,
          e.pinstIdResol,
          relationshipId,
          signal.threadId,
          None
        )

        PinstIdPair(pinst, e.protoDef)
      }

    val values = for (
      p <- toPinstPair;
      m <- msg
    ) yield (p, m)

    values match {
      case Success((p, m)) =>
        sendToAgentMsgProcessor(SendToProtocolActor(p, m, self))
      case Failure(exception) => logger.warn(
        s"Unable to Move protocol from rel ${signal.fromRelationship} to ${signal.toRelationship} " +
          s"for thread: ${signal.threadId} on a ${signal.protoRefStr}",
        exception
      )
    }

    Future.successful(None)
  }

  def handleUpdateTheirDidDoc(stdd: SetupTheirDidDoc):Future[Option[ControlMsg]] = {
    //TODO: modify this to efficiently route to itself if this is pairwise actor itself
    if (state.theirDidDoc.isEmpty) {
      updateTheirDidDoc(stdd)
      for {
        _   <- setRoute(stdd.myDID)
        ctlMsg  <-
          agencyDidPairFut().map { agencyDidPair =>
            val myVerKey = state.myDidAuthKeyReq.verKey
            val routingKeys = Vector(agencyDidPair.verKey)
            Option(ControlMsg(TheirDidDocUpdated(state.myDid_!, myVerKey, routingKeys)))
          }
      } yield ctlMsg
    } else {
      throw new BadRequestErrorException(Status.UNAUTHORIZED.statusCode, Option("unauthorized access"))
    }
  }

  def updateTheirDidDoc(stdd: SetupTheirDidDoc): Unit = {
    val tpdd = TheirProvisionalDidDocDetail(stdd.theirDID.getOrElse(""), stdd.theirVerKey,
      stdd.theirServiceEndpoint, stdd.theirRoutingKeys)
    val csu = ConnectionStatusUpdated(reqReceived=true, answerStatusCode=MSG_STATUS_ACCEPTED.statusCode,
      theirProvisionalDidDocDetail=Option(tpdd))
    writeAndApply(csu)
  }

  def getInternalReqHelperData(implicit reqMsgContext: ReqMsgContext): InternalReqHelperData =
    InternalReqHelperData(reqMsgContext)

  def msgPackFormat(msgId: MsgId): MsgPackFormat =
    getMsgPayload(msgId).flatMap(_.msgPackFormat).getOrElse(MPF_MSG_PACK)

  def agentConfigs: Map[String, AgentConfig] = state.agentConfigs

  def agencyDIDOpt: Option[DID] = state.agencyDIDPair.map(_.DID)

  def ownerDID: Option[DID] = state.mySelfRelDID
  def ownerAgentKeyDIDPair: Option[DidPair] = state.ownerAgentDidPair

  def mySelfRelDIDReq: DID = domainId
  def myPairwiseVerKey: VerKey = state.myDidAuthKeyReq.verKey

  lazy val scheduledJobInterval: Int = appConfig.getIntOption(
    USER_AGENT_PAIRWISE_ACTOR_SCHEDULED_JOB_INTERVAL_IN_SECONDS).getOrElse(300)

  /**
   * there are different types of actors (agency agent, agency pairwise, user agent and user agent pairwise)
   * when we store the persistence detail, we store these unique id for each of them
   * which then used during routing to know which type of region actor to be used to route the message
   *
   * @return
   */
  override def actorTypeId: Int = ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR

  override def afterStop(): Unit = {
    metricsActorRef ! RemoveCollectionMetric(COLLECTION_METRIC_MND_MSGS_TAG, this.actorId)
    metricsActorRef ! RemoveCollectionMetric(COLLECTION_METRIC_MND_MSGS_DELIVRY_STATUS_TAG, this.actorId)
    metricsActorRef ! RemoveCollectionMetric(COLLECTION_METRIC_MND_MSGS_DETAILS_TAG, this.actorId)
    metricsActorRef ! RemoveCollectionMetric(COLLECTION_METRIC_MND_MSGS_PAYLOADS_TAG, this.actorId)
  }
}

object UserAgentPairwise {
  final val COLLECTION_METRIC_MND_MSGS_TAG = "user-agent-pairwise.mnd.msgs"
  final val COLLECTION_METRIC_MND_MSGS_PAYLOADS_TAG = "user-agent-pairwise.mnd.msgs-payloads"
  final val COLLECTION_METRIC_MND_MSGS_DETAILS_TAG = "user-agent-pairwise.mnd.msgs-details"
  final val COLLECTION_METRIC_MND_MSGS_DELIVRY_STATUS_TAG = "user-agent-pairwise.mnd.msgs-delivery-status"
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
                                          reqHelperData: InternalReqHelperData) extends ActorMessage

case class AddTheirDidDoc(theirDIDDoc: LegacyDIDDoc) extends ActorMessage

trait UserAgentPairwiseStateImpl
  extends AgentStatePairwiseImplBase
    with UserAgentCommonState { this: UserAgentPairwiseState =>

  def domainId: DomainId = mySelfRelDID.getOrElse(throw new RuntimeException("domainId not available"))

  def checkIfMsgAlreadyNotInAnsweredState(msgId: MsgId): Unit = {
    if (msgAndDelivery.map(_.msgs).getOrElse(Map.empty).get(msgId)
      .exists(m => MsgHelper.validAnsweredMsgStatuses.contains(m.statusCode))){
      throw new BadRequestErrorException(MSG_VALIDATION_ERROR_ALREADY_ANSWERED.statusCode,
        Option("msg is already answered (uid: " + msgId + ")"))
    }
  }
}

trait UserAgentPairwiseStateUpdateImpl
  extends UserAgentCommonStateUpdateImpl { this : UserAgentPairwise =>

  def msgAndDelivery: Option[MsgAndDelivery] = state.msgAndDelivery

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

  def addConfig(name: String, ac: AgentConfig): Unit = {
    state = state.withConfigs(state.configs ++ Map(name -> ac.toConfigValue))
  }

  def removeConfig(name: String): Unit = {
    state = state.withConfigs(state.configs.filterNot(_._1 == name))
  }

  def updateMsgAndDelivery(msgAndDelivery: MsgAndDelivery): Unit = {
    state = state.withMsgAndDelivery(msgAndDelivery)
    val m = state.msgAndDelivery.get
    metricsActorRef ! UpdateCollectionMetric(COLLECTION_METRIC_MND_MSGS_TAG, this.actorId, m.msgs.size)
    metricsActorRef ! UpdateCollectionMetric(COLLECTION_METRIC_MND_MSGS_DELIVRY_STATUS_TAG, this.actorId, m.msgDeliveryStatus.size)
    metricsActorRef ! UpdateCollectionMetric(COLLECTION_METRIC_MND_MSGS_DETAILS_TAG, this.actorId, m.msgDetails.size)
    metricsActorRef ! UpdateCollectionMetric(COLLECTION_METRIC_MND_MSGS_PAYLOADS_TAG, this.actorId, m.msgPayloads.size)
  }

  def updateConnectionStatus(reqReceived: Boolean, answerStatusCode: String): Unit = {
    state = state.withConnectionStatus(ConnectionStatus(reqReceived, answerStatusCode))
  }

  override def updateAgencyDidPair(dp: DidPair): Unit = {
    state = state.withAgencyDIDPair(dp)
  }

  override def updateRelationship(rel: Relationship): Unit = {
    state = state.withRelationship(rel)
    updateStateWithOwnerAgentKey()
  }
}