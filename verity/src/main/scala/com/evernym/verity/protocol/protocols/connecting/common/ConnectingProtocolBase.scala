package com.evernym.verity.protocol.protocols.connecting.common

import akka.actor.Actor.Receive
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, InvalidValueException}
import com.evernym.verity.util2.Status.{getStatusMsgFromCode, _}
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.msghandler.outgoing.NotifyMsgDetail
import com.evernym.verity.actor.agent.msgsender.{AgentMsgSender, SendMsgParam}
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.actor.agent.{AttrName, AttrValue, EncryptionParamBuilder, MsgPackFormat, PayloadMetadata}
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK, MPF_PLAIN, Unrecognized}
import com.evernym.verity.agentmsg.msgfamily.AgentMsgContext
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise.{ConnectingMsgHelper, _}
import com.evernym.verity.agentmsg.msgpacker._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.actor.appStateManager.AppStateEvent
import com.evernym.verity.actor.wallet.{CreateNewKey, CreateWallet, GetVerKey, GetVerKeyResp, NewKeyCreated, PackedMsg, StoreTheirKey, TheirKeyStored, WalletCreated}
import com.evernym.verity.cache.base.Cache
import com.evernym.verity.did.{DidStr, DidPair, VerKeyStr}
import com.evernym.verity.vault.operation_executor.{CryptoOpExecutor, VerifySigByVerKey}
import com.evernym.verity.protocol.container.actor._
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols._
import com.evernym.verity.protocol.protocols.connecting.v_0_5.{ConnectingMsgFamily => ConnectingMsgFamily_0_5}
import com.evernym.verity.protocol.protocols.connecting.v_0_6.{ConnectingMsgFamily => ConnectingMsgFamily_0_6}
import com.evernym.verity.protocol.{Control, HasMsgType}
import com.evernym.verity.push_notification.{PushNotifData, PushNotifMsgBuilder}
import com.evernym.verity.transports.MsgSendingSvc
import com.evernym.verity.util.Base64Util
import com.evernym.verity.util.TimeZoneUtil.getMillisForCurrentUTCZonedDateTime
import com.evernym.verity.util.Util._
import com.evernym.verity.vault._
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.util2.{Exceptions, MsgPayloadStoredEventBuilder, Status, UrlParam}
import com.typesafe.scalalogging.Logger
import org.json.JSONObject

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Left

sealed trait Role

//TODO: currently ConnectingProtocol handles Connecting specific messages and few others
// (like InitParams, AgentMsgWrapper, GetMsgsReqMsg, UpdateMsgExpirationTime, UpdateMsgDeliveryStatus)
// which can be used in other places/protocols too, so for now, using ProtoMsg (which is generic in a way)
// for message type

//TODO: In this protocol, mostly for validation purposes, we are calling methods on 'state' object,
//we will have to eventually refactor it

//TODO: Currently, the 'ConnectingState' is a class (not a case class), so we may wanna revisit that too


trait ConnectingProtocolBase[P,R,S <: ConnectingStateBase[S],I]
    extends HasAppConfig
    with AgentMsgSender
    with MsgDeliveryResultHandler
    with PushNotifMsgBuilder
    with ConnReqAnswerMsgHandler[S]
    with ConnReqMsgHandler[S]
    with ConnReqRedirectMsgHandler[S]
    with DEPRECATED_HasWallet
    with HasLogger { this: Protocol[P,R,ProtoMsg,Any,S,I] =>

  val logger: Logger = ctx.logger

  def publishAppStateEvent (event: AppStateEvent): Unit = {
    ctx.SERVICES_DEPRECATED.publishAppStateEvent(event)
  }
  def encryptionParamBuilder: EncryptionParamBuilder = EncryptionParamBuilder()

  def agencyDIDReq: DidStr = ctx.getState.agencyDIDOpt.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("agency DID not found"))
  )

  def applyEventBase: ApplyEvent = {
    case (_, _, e: ProtocolInitialized) =>
      val is = initState(e.parameters)
      is.parameters = Parameters(e.parameters.map(p => Parameter(p.name, p.value)).toSet)
      is.stateStr = INITIALIZED
      val roster = initialize(e.parameters)
      (is, Option(roster))

    //kept for backward compatibility (for existing stored events)
    case (s, _, _: ProtocolObserverAdded) => s

    case (s, _, kd: AgentKeyDlgProofSet) =>
      s.agentKeyDlgProof = Some(AgentKeyDlgProof(kd.DID, kd.delegatedKey, kd.signature))
      s

    case (s, _, e) =>
      pairwiseConnEventReceiver(e)
      e match {
        case mc: MsgCreated if mc.statusCode == Status.MSG_STATUS_CREATED.statusCode =>
          s.stateStr = REQ_CREATED
        case csu: ConnectionStatusUpdated =>
          csu.answerStatusCode match {
            case MSG_STATUS_ACCEPTED.statusCode => s.stateStr = ACCEPTED
            case MSG_STATUS_REJECTED.statusCode => s.stateStr = REJECTED
            case MSG_STATUS_SENT.statusCode     => s.stateStr = REQ_SENT
            case MSG_STATUS_RECEIVED.statusCode => s.stateStr = REQ_RECEIVED
            case _ => // do nothing.
          }
        case _ => // do nothing.
      }
      s
  }

  protected def handleInitParams(ip: Init): Unit = {
    ctx.apply(ProtocolInitialized(ip.parametersStored.toSeq))
  }

  def initialize(params: Seq[ParameterStored]): Roster[R] = {
    ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
  }

  protected def verifyAgentKeyDlgProof(agentKeyDlgProof: AgentKeyDlgProof, signedByVerKey: VerKeyStr,
                                       isEdgeAgentsKeyDlgProof: Boolean): Unit = {
    val challenge = agentKeyDlgProof.buildChallenge.getBytes
    val sig = Base64Util.getBase64Decoded(agentKeyDlgProof.signature)
    val vs = VerifySigByVerKey(signedByVerKey, challenge, sig)
    val verifResult = ctx.DEPRECATED_convertAsyncToSync(CryptoOpExecutor.verifySig(vs))
    if (! verifResult.verified) {
      val errorMsgPrefix = if (isEdgeAgentsKeyDlgProof) "local" else "remote"
      val errorMsg = errorMsgPrefix + " agent key delegation proof verification failed"
      throw new BadRequestErrorException(SIGNATURE_VERIF_FAILED.statusCode, Option(errorMsg))
    }
  }

  protected def buildSendMsgResp(uid: MsgId)(implicit agentMsgContext: AgentMsgContext): List[Any] = {
    logger.debug(s"[$uid] message sending started")
    val msg = ctx.getState.connectingMsgState.getMsgReq(uid)
    val msgsToBeSent = msg.getType match {
      case CREATE_MSG_TYPE_CONN_REQ =>
        //to send connection request asynchronously (to given sms if any)
        List(SendConnReqMsg(uid))

      case CREATE_MSG_TYPE_CONN_REQ_ANSWER | MSG_TYPE_ACCEPT_CONN_REQ |
           CREATE_MSG_TYPE_REDIRECT_CONN_REQ| MSG_TYPE_REDIRECT_CONN_REQ if msg.sendMsg =>
        //to send connection request answer asynchronously (to remote cloud agent)
        List(SendMsgToRemoteCloudAgent(uid, agentMsgContext.msgPackFormatToBeUsed))

      case CREATE_MSG_TYPE_CONN_REQ_ANSWER | MSG_TYPE_CONN_REQ_ACCEPTED |
           CREATE_MSG_TYPE_CONN_REQ_REDIRECTED | MSG_TYPE_CONN_REQ_REDIRECTED =>
        agentMsgContext.msgPackFormat match {
          case MPF_INDY_PACK | MPF_PLAIN =>
            List.empty
          case MPF_MSG_PACK =>
            //to send received connection request answer asynchronously (to edge agent)
            List(SendMsgToEdgeAgent(uid))
          case Unrecognized(_) => throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
        }

      case _ => List.empty//nothing to do
    }
    msgsToBeSent.foreach{ msgToBeSent =>
      ctx.SERVICES_DEPRECATED.msgQueueServiceProvider.addToMsgQueue(msgToBeSent)
    }
    if (msg.sendMsg) SendMsgsMsgHelper.buildRespMsg(List(uid))(agentMsgContext)
    else List.empty
  }

  def getMsgDetails(uid: MsgId): Map[AttrName, AttrValue] = {
    ctx.getState.connectingMsgState.getMsgDetails(uid)
  }

  def getMsgDetail(uid: MsgId, key: AttrName): Option[AttrValue] = {
    getMsgDetails(uid).get(key)
  }

  def getMsgDetailReq(uid: MsgId, key: AttrName): AttrValue = {
    getMsgDetail(uid, key).getOrElse(throw new RuntimeException(s"message detail not found, uid: $uid, key: $key"))
  }

  private def buildConnReqAnswerMsgForRemoteCloudAgent(uid: MsgId): List[Any] = {
    val answerMsg = ctx.getState.connectingMsgState.getMsgReq(uid)
    val reqMsgIdOpt = ctx.getState.connectingMsgState.getReplyToMsgId(uid)
    logger.debug("reqMsgIdOpt when building conn request answer msg: " + reqMsgIdOpt)
    val senderDetail = SenderDetail(ctx.getState.myPairwiseDIDReq,
      myPairwiseVerKeyReq, ctx.getState.agentKeyDlgProof, None, None, None)
    val senderAgencyDetail: SenderAgencyDetail = SenderAgencyDetail(
      agencyDIDReq, ctx.getState.parameters.paramValueRequired(AGENCY_DID_VER_KEY),
      buildAgencyEndpoint(appConfig).toString)

    answerMsg.`type` match {
      case MSG_TYPE_ACCEPT_CONN_REQ | CREATE_MSG_TYPE_CONN_REQ_ANSWER =>
        ConnectingMsgHelper.buildConnReqAnswerMsgForRemoteCloudAgent(
          msgPackFormat,
          uid,
          answerMsg.statusCode,
          reqMsgIdOpt.get,
          senderDetail,
          senderAgencyDetail,
          threadIdReq)

      case MSG_TYPE_REDIRECT_CONN_REQ | CREATE_MSG_TYPE_REDIRECT_CONN_REQ =>
        val redirectDetail = new JSONObject(getMsgDetailReq(uid, REDIRECT_DETAIL))
        ConnectingMsgHelper.buildRedirectedConnReqMsgForRemoteCloudAgent(
          msgPackFormat,
          uid,
          reqMsgIdOpt.get,
          redirectDetail,
          senderDetail,
          senderAgencyDetail,
          threadIdReq)
    }

  }

  private def buildSendMsgParam(uid: MsgId, msgType: String, msg: Array[Byte], isItARetryAttempt: Boolean=false): SendMsgParam = {
    val s = ctx.getState
    SendMsgParam(uid, msgType, msg, agencyDIDReq, s.state.theirRoutingParam, isItARetryAttempt)
  }

  lazy val msgPackFormat: MsgPackFormat =
    if (definition.msgFamily.protoRef.msgFamilyVersion == MFV_0_5) MPF_MSG_PACK else MPF_INDY_PACK

  protected def sendMsgToRemoteCloudAgent(uid: MsgId, msgPackFormat: MsgPackFormat): Unit = {
    val answeredMsg = ctx.getState.connectingMsgState.getMsgReq(uid)
    try {
      val agentMsgs: List[Any] = buildConnReqAnswerMsgForRemoteCloudAgent(uid)
      val packedMsg = buildReqMsgForTheirRoutingService(msgPackFormat, agentMsgs, msgPackFormat == MPF_MSG_PACK, answeredMsg.`type`)
      sendToTheirAgencyEndpoint(buildSendMsgParam(uid, answeredMsg.getType, packedMsg.msg), ctx.metricsWriter)
    } catch {
      case e: Exception =>
        logger.error("sending invite answered msg to remote agency failed", Exceptions.getErrorMsg(e))
        throw e
    }
  }

  protected def sendMsgToEdgeAgent(uid: MsgId): Unit = {
    ctx.getState.connectingMsgState.getMsgPayload(uid).foreach { pw =>
      ctx.signal(SendMsgToRegisteredEndpoint(uid, pw.msg, pw.metadata))
    }
  }

  protected def buildInviteDetail(uid: MsgId, checkIfExpired: Boolean = true): InviteDetail = {
    val msg = ctx.getState.connectingMsgState.getMsgReq(uid)
    if (checkIfExpired) checkIfMsgNotExpired(uid)
    val agencyVerKey = ctx.getState.parameters.paramValueRequired(AGENCY_DID_VER_KEY)
    val msgDetails = getMsgDetails(uid)
    val targetName = msgDetails.getOrElse(TARGET_NAME, DEFAULT_INVITE_RECEIVER_USER_NAME)
    val includePublicDID = msgDetails.get(INCLUDE_PUBLIC_DID).filter(_ == YES)
    val publicDID = if (includePublicDID.isDefined) Option(ctx.getState.myPublicDIDReq) else None
    val senderAgencyDetail = SenderAgencyDetail(agencyDIDReq, agencyVerKey,
      buildAgencyEndpoint(appConfig).toString)
    val senderDetail = SenderDetail(ctx.getState.myPairwiseDIDReq, myPairwiseVerKeyReq, ctx.getState.agentKeyDlgProof,
      Option(userName_!), Option(userLogoUrl_!), publicDID)
    InviteDetail(uid, targetName, senderAgencyDetail, senderDetail,
      msg.statusCode, getStatusMsgFromCode(msg.statusCode), inviteDetailVersion)
  }


  def checkNoAcceptedInvitationExists(): Unit = {
    if (ctx.getState.state.connectionStatus.exists(_.answerStatusCode == MSG_STATUS_ACCEPTED.statusCode)) {
      throw new BadRequestErrorException(ACCEPTED_CONN_REQ_EXISTS.statusCode)
    }
  }

  def checkIfMsgNotExpired(uid: MsgId): Unit = {
    val msg = ctx.getState.connectingMsgState.getMsgReq(uid)
    val expired = ctx.getState.connectingMsgState.getMsgExpirationTime(msg.getType) match {
      case Some(t) => isExpired(msg.creationDateTime, t)
      case None =>
        val configName = expiryTimeInSecondConfigNameForMsgType(msg.getType)
        appConfig.getIntOption(configName).exists(s => isExpired(msg.creationDateTime, s))
    }
    if (expired) {
      throw new BadRequestErrorException(MSG_VALIDATION_ERROR_EXPIRED.statusCode)
    }
  }

  protected def handleGetInviteDetail(gid: GetInviteDetail): InviteDetail = {
    if (ctx.getState.connectingMsgState.getMsgOpt(gid.uid).isEmpty) {
      throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode)
    } else {
      buildInviteDetail(gid.uid)
    }
  }

  protected def checkIfReplyMsgIdProvided(replyToMsgId: String): Unit = {
    if (Option(replyToMsgId).isEmpty) {
      throw new BadRequestErrorException(MISSING_REQ_FIELD.statusCode, Option("missing required attribute: 'replyToMsgId'"))
    }
  }

  protected def checkConnReqMsgIfExistsNotExpired(connReqMsgId: String): Unit = {
    ctx.getState.connectingMsgState.getMsgOpt(connReqMsgId) match {
      case Some(_) => checkIfMsgNotExpired(connReqMsgId)
      case None => //
    }
  }

  def prepareEdgePayloadStoredEvent(msgId: String, msgName: String, externalPayloadMsg: String): Option[MsgPayloadStored] = {

    def prepareEdgeMsg(): Array[Byte] = {
      val fromKeyParam = KeyParam.fromVerKey(ctx.getState.thisAgentVerKeyReq)
      val forKeyParam = KeyParam.fromDID(getEncryptForDID)
      val encryptParam = EncryptParam (Set(forKeyParam), Option(fromKeyParam))
      val packedMsg = awaitResult(ctx.SERVICES_DEPRECATED.agentMsgTransformer.packAsync(
        msgPackFormat, externalPayloadMsg, encryptParam))
      packedMsg.msg
    }
    val payload = prepareEdgeMsg()
    val payloadStoredEvent = MsgPayloadStoredEventBuilder.buildMsgPayloadStoredEvt(
      msgId, payload, Option(PayloadMetadata(msgName, msgPackFormat)))
    Some(payloadStoredEvent)
  }

  def getSourceIdFor(uid: MsgId): Option[String] = {
    getMsgDetail(uid, SOURCE_ID)
  }

  protected def handleUpdateMsgExpirationTime(umet: UpdateMsgExpirationTime): Unit = {
    ctx.apply(MsgExpirationTimeUpdated(umet.targetMsgName, umet.timeInSeconds))
  }

  protected def handleUpdateMsgDeliveryStatus(umds: UpdateMsgDeliveryStatus): Unit = {
    val event = MsgDeliveryStatusUpdated(umds.uid, umds.to, umds.statusCode,
      umds.statusDetail.getOrElse(Evt.defaultUnknownValueForStringType), getMillisForCurrentUTCZonedDateTime)
    ctx.apply(event)
    DEPRECATED_sendSpecialSignal(UpdateDeliveryStatus(event))
  }

  protected def handleMsgSentSuccessfully(mss: MsgSentSuccessfully): Unit = {
    buildPushNotifDataForSuccessfulMsgDelivery(NotifyMsgDetail(mss.uid, mss.typ)).foreach { pnd =>
      ctx.signal(NotifyUserViaPushNotif(pnd, updateDeliveryStatus = false))
    }
  }

  protected def handleMsgSendingFailed(msf: MsgSendingFailed): Unit = {
    buildPushNotifDataForFailedMsgDelivery(NotifyMsgDetail(msf.uid, msf.typ)).foreach { pnd =>
      ctx.signal(NotifyUserViaPushNotif(pnd, updateDeliveryStatus = false))
    }
  }

  protected def handleGetStatusReqMsg(m: GetStatusReqMsg_MFV_0_6): Unit = {
    ctx.signal(StatusReport(m.sourceId, ctx.getState.stateStr))
  }

  override def updateMsgDeliveryStatus(uid: MsgId, to: String,
                                       statusCode: String, statusMsg: Option[String]=None): Unit = {
    val uds = UpdateMsgDeliveryStatus(uid, to, statusCode, statusMsg)
    ctx.SERVICES_DEPRECATED.msgQueueServiceProvider.addToMsgQueue(uds)
  }

  override def msgSentSuccessfully(mss: MsgSentSuccessfully): Unit = {
    ctx.SERVICES_DEPRECATED.msgQueueServiceProvider.addToMsgQueue(mss)
  }

  override def msgSendingFailed(msf: MsgSendingFailed): Unit = {
    ctx.SERVICES_DEPRECATED.msgQueueServiceProvider.addToMsgQueue(msf)
  }

  def buildMsgCreatedEvt(msgId: MsgId,
                         mType: String,
                         senderDID: DidStr,
                         sendMsg: Boolean,
                         threadOpt: Option[Thread]=None): MsgCreated = {
    val msgStatus =
      if (senderDID == ctx.getState.myPairwiseDIDReq) MSG_STATUS_CREATED.statusCode
      else MSG_STATUS_RECEIVED.statusCode
    ctx.getState.connectingMsgState.buildMsgCreatedEvt(msgId , mType, senderDID, sendMsg, msgStatus, threadOpt)
  }

  def initState(params: Seq[ParameterStored]): S

  def inviteDetailVersion: String

  def getEncryptForDID: DidStr

  def buildAgentPackedMsg(msgPackFormat: MsgPackFormat, param: PackMsgParam): PackedMsg = {
    val fut = AgentMsgPackagingUtil.buildAgentMsg(msgPackFormat, param)(agentMsgTransformer, wap, ctx.metricsWriter)
    awaitResult(fut)
  }

  def awaitResult(fut: Future[PackedMsg]): PackedMsg = {
    Await.result(fut, 5.seconds)
  }

  def threadIdReq: ThreadId = ctx.getInFlight.threadId.getOrElse(
    throw new RuntimeException("thread id required, but not found")
  )

  //Duplicate code starts (same code exists in 'PairwiseConnState')

  def pairwiseConnEventReceiver: Receive =
    ctx.getState.pairwiseConnReceiver orElse
      ctx.getState.connectingMsgState.msgEventReceiver

  def isUserPairwiseVerKey(verKey: VerKeyStr): Boolean = {
    val userPairwiseVerKey = getVerKeyReqViaCache(ctx.getState.myPairwiseDIDReq).verKey
    verKey == userPairwiseVerKey
  }

  def encParamBasedOnMsgSender(senderVerKeyOpt: Option[VerKeyStr]): EncryptParam = {
    senderVerKeyOpt match {
      case Some(vk) =>
        if (isUserPairwiseVerKey(vk)) encParamFromThisAgentToOwner
        else if (ctx.getState.state.isTheirAgentVerKey(vk))
          encryptionParamBuilder.withRecipDID(ctx.getState.theirAgentKeyDIDReq)
          .withSenderVerKey(ctx.getState.thisAgentVerKeyReq).encryptParam
        else encryptionParamBuilder.withRecipVerKey(vk)
          .withSenderVerKey(ctx.getState.thisAgentVerKeyReq).encryptParam
      case None => throw new InvalidValueException(Option("no sender ver key found"))
    }
  }

  def buildReqMsgForTheirRoutingService(msgPackFormat: MsgPackFormat, agentMsgs: List[Any],
                                        wrapInBundledMsgs: Boolean, msgType: String): PackedMsg = {
    (ctx.getState.state.theirDIDDoc.flatMap(_.legacyRoutingDetail), ctx.getState.state.theirDIDDoc.flatMap(_.routingDetail)) match {
      case (Some(_: LegacyRoutingDetail), None) =>
        val packMsgParam = PackMsgParam(
          encryptionParamBuilder.withRecipDID(ctx.getState.theirAgentKeyDIDReq)
            .withSenderVerKey(ctx.getState.thisAgentVerKeyReq).encryptParam,
          agentMsgs, wrapInBundledMsgs)
        val packedMsgFut = AgentMsgPackagingUtil.buildAgentMsg(msgPackFormat, packMsgParam)(agentMsgTransformer, wap, ctx.metricsWriter)
        val packedMsg = awaitResult(packedMsgFut)
        buildRoutedPackedMsgForTheirRoutingService(msgPackFormat, packedMsg.msg, msgType)
      case x => throw new RuntimeException("unsupported routing detail" + x)
    }
  }

  def buildRoutedPackedMsgForTheirRoutingService(msgPackFormat: MsgPackFormat, packedMsg: Array[Byte], msgType: String): PackedMsg = {
    val result = (ctx.getState.state.theirDIDDoc.flatMap(_.legacyRoutingDetail), ctx.getState.state.theirDIDDoc.flatMap(_.routingDetail)) match {
      case (Some(v1: LegacyRoutingDetail), None) =>
        val theirAgencySealParam = SealParam(KeyParam(Left(getVerKeyReqViaCache(
          v1.agencyDID, getKeyFromPool = GET_AGENCY_VER_KEY_FROM_POOL).verKey)))
        val fwdRouteForAgentPairwiseActor = FwdRouteMsg(v1.agentKeyDID, Left(theirAgencySealParam))
        AgentMsgPackagingUtil.buildRoutedAgentMsg(msgPackFormat, PackedMsg(packedMsg),
          List(fwdRouteForAgentPairwiseActor))(agentMsgTransformer, wap, ctx.metricsWriter)
      case (None, Some(v2: RoutingDetail)) =>
        val routingKeys = if (v2.routingKeys.nonEmpty) Vector(v2.verKey) ++ v2.routingKeys else v2.routingKeys
        AgentMsgPackagingUtil.packMsgForRoutingKeys(
          MPF_INDY_PACK,
          packedMsg,
          routingKeys,
          msgType
        )(agentMsgTransformer, wap, ctx.metricsWriter)
      case x => throw new RuntimeException("unsupported routing detail" + x)
    }
    awaitResult(result)
  }
  //Duplicate code ends (same code exists in 'PairwiseConnState')

  val PHONE_NUMBER = "phone-number"

  val REQ_CREATED = "RequestCreated"
  val REQ_SENT = "RequestSent"
  val REQ_RECEIVED = "RequestReceived"
  val ACCEPTED = "ConnectionAccepted"
  val REJECTED = "ConnectionRejected"

  val REDIRECT_DETAIL = "redirect-detail"

  override def msgRecipientDID: DidStr = myPairwiseDIDReq
  override lazy val appConfig: AppConfig = ctx.SERVICES_DEPRECATED.appConfig
  override lazy val msgSendingSvc: MsgSendingSvc = ctx.SERVICES_DEPRECATED.msgSendingSvc
  override lazy val generalCache: Cache = ctx.SERVICES_DEPRECATED.generalCache
  override implicit lazy val agentMsgTransformer: AgentMsgTransformer = ctx.SERVICES_DEPRECATED.agentMsgTransformer

  def myPairwiseDIDReq: DidStr
  def myPairwiseVerKeyReq: VerKeyStr

  lazy val `userName_!`: String = ctx.getState.parameters.paramValueRequired(NAME)
  lazy val `userLogoUrl_!`: String = ctx.getState.parameters.paramValueRequired(LOGO_URL)

  override def walletAPI: WalletAPI = ctx.SERVICES_DEPRECATED.walletAPI

  /**
   * this method should not be used anywhere else than where it is already used
   * this will send signal message which will be directly handled by UserAgentCommon
   * to replicate the msg state in that agent actor
   * @param msg
   */
  def DEPRECATED_sendSpecialSignal(msg: LegacyConnectingSignal): Unit = {
    ctx.signal(msg)
  }
}

case class NotifyUserViaPushNotif(pushNotifData: PushNotifData, updateDeliveryStatus: Boolean)

case class SendMsgToRegisteredEndpoint(msgId: MsgId, msg: Array[Byte], metadata: Option[PayloadMetadata]) extends Control with MsgBase with ActorMessage

/**
 * This is used after connection request message is validated and its events are sent for persistence.
 * It is sent from Connecting Protocol to the protocol container (in this case GenericProtocolActor)
 * which then sends that message to self so that it will be guaranteed that it will be only picked up
 * by GenericProtocolActor when previous events would have been successfully persisted and applied.
 * As part of processing this message, it asynchronously
 *   a. sends the invite sms.
 *   b. prepare the response (of original connReq message) and sends it back to caller.
 * @param uid - connection request message uid
 */
case class SendConnReqMsg(uid: MsgId) extends Control with ActorMessage

/**
 * This is used after connection request answer message is validated and its events are sent for persistence.
 * It is sent from Connecting Protocol to the protocol container (in this case GenericProtocolActor)
 * which then sends that message to self so that it will be guaranteed that it will be only picked up
 * by GenericProtocolActor when previous events would have been successfully persisted and applied.
 * As part of processing this (on connection request acceptor side), it sends the received message
 * to connection request sender's cloud agent
 * @param uid - connection request answer message uid
 */
case class SendMsgToRemoteCloudAgent(uid: MsgId, msgPackFormat: MsgPackFormat) extends Control with ActorMessage


/**
 * This is used after connection request answer message is validated and its events are sent for persistence.
 * It is sent from Connecting Protocol to the protocol container (in this case GenericProtocolActor)
 * which then sends that message to self so that it will be guaranteed that it will be only picked up
 * by GenericProtocolActor when previous events would have been successfully persisted and applied.
 * As part of processing this (on connection request sender side), it sends the received message to edge agent
 * @param uid - accepted conn request message id
 */
case class SendMsgToEdgeAgent(uid: MsgId) extends Control with ActorMessage

// signal
case class StatusReport(sourceId: String, status: String)

trait GetInviteDetail extends ProtoMsg with HasMsgType {
  def uid: MsgId
}

/**
 * Used to update timeout restrictions to a message, determining how long it is valid
 * Currently, it is only used to set expiration time for a connection request msg
 *  Sent from the Pairwise Actor to the Connection Protocol Actor
 *
 *  targetMsgName - connReq is the only message currently that has an expiration
 */
trait UpdateMsgExpirationTime extends Control with HasMsgType {
  def targetMsgName: MsgName
  def timeInSeconds: Int

  val msgName: MsgFamilyName = MSG_TYPE_UPDATE_EXPIRY_TIME
}

case class UpdateMsgExpirationTime_MFV_0_5(targetMsgName: MsgName, timeInSeconds: Int) extends UpdateMsgExpirationTime {
  val msgFamily: MsgFamily = ConnectingMsgFamily_0_5
}

case class UpdateMsgExpirationTime_MFV_0_6(targetMsgName: MsgName, timeInSeconds: Int) extends UpdateMsgExpirationTime {
  val msgFamily: MsgFamily = ConnectingMsgFamily_0_6
}

/**
 * Used during sending connection request url via sms.
 * As part of processing this message, it creates invitation url
 * and then stores its mapping with shortened url which can be sent in an sms.
 * This is used in Connecting Protocol
 * @param uid - connection request message's uid
 * @param phoneNo - invite recipient's phone number
 * @param urlMappingServiceEndpoint - endpoint of url mapping service (today hosted on CAS [Consumer Agent Service])
 */
case class CreateAndSendTinyUrlParam(uid: MsgId, phoneNo: String, urlMappingServiceEndpoint: UrlParam)

/**
 * Used during preparing invitation detail and also when connection request answer message is sent to remote cloud agent.
 * It provides connection participant's agency details (DID, endpoint etc) to the other side of a connection.
 *
 * @param endpoint - agency endpoint of the connection requester or connection request responder
 */
case class SenderAgencyDetail(DID: DidStr, verKey: VerKeyStr, endpoint: String) {
  def toAbbreviated: SenderAgencyDetailAbbreviated = {
    SenderAgencyDetailAbbreviated(DID, verKey, endpoint)
  }
}

case class SenderAgencyDetailAbbreviated(d: DidStr, v: VerKeyStr, e: String)

/**
 * Used during preparing invitation detail and also when connection request answer message is sent to remote cloud agent.
 * It provides connection participant's pairwise information to the other side of a connection.
 */
case class SenderDetail(DID: DidStr, verKey: VerKeyStr, agentKeyDlgProof: Option[AgentKeyDlgProof], name: Option[String],
                        logoUrl: Option[String], publicDID: Option[DidStr]) {
  def toAbbreviated: SenderDetailAbbreviated = {
    SenderDetailAbbreviated(
      DID,
      verKey,
      agentKeyDlgProof.map(_.toAbbreviated),
      name,
      logoUrl,
      publicDID
    )
  }
}

case class SenderDetailAbbreviated(d: DidStr, v: VerKeyStr, dp: Option[AgentKeyDlgProofAbbreviated], n: Option[String],
                                   l: Option[String], publicDID: Option[DidStr])


/**
 * Used to provide the connection initiator's pairwise information and agent info to the targeted connection
 *  Sent from the Connection Protocol to the connection target
 */
case class InviteDetail(connReqId: String, targetName: String,
                        senderAgencyDetail: SenderAgencyDetail, senderDetail: SenderDetail,
                        statusCode: String, statusMsg: String, version: String) extends ActorMessage {
  def toAbbreviated: InviteDetailAbbreviated = {
    InviteDetailAbbreviated(
      connReqId,
      targetName,
      senderAgencyDetail.toAbbreviated,
      senderDetail.toAbbreviated,
      statusCode,
      statusMsg,
      version
    )
  }
}

case class InviteDetailAbbreviated(id: String, t: String, sa: SenderAgencyDetailAbbreviated, s: SenderDetailAbbreviated,
                                   sc: String, sm: String, version: String) extends ActorMessage

case class ConnReqReceived(inviteDetail: InviteDetail) extends MsgBase

trait LegacyConnectingSignal extends MsgBase

case object AddMsg {
  def apply(msgCreated: MsgCreated, payload: Option[Array[Byte]]=None): AddMsg = {
    val refMsgId = if (msgCreated.refMsgId == "") None else Option(msgCreated.refMsgId)
    AddMsg(msgCreated.uid, msgCreated.typ, msgCreated.senderDID, msgCreated.statusCode, msgCreated.sendMsg, payload, refMsgId)
  }
}
case class AddMsg(msgId: MsgId, msgType: String, senderDID: DidStr, statusCode: String, sendMsg: Boolean,
                  payload: Option[Array[Byte]], refMsgId: Option[MsgId]) extends LegacyConnectingSignal

case class UpdateMsg(msgId: MsgId, statusCode: String, refMsgId: Option[MsgId]) extends LegacyConnectingSignal

case object UpdateDeliveryStatus {
  def apply(umds: MsgDeliveryStatusUpdated): UpdateDeliveryStatus = {
    UpdateDeliveryStatus(umds.uid, umds.to, umds.statusCode, Evt.getOptionFromValue(umds.statusDetail))
  }
}
case class UpdateDeliveryStatus(msgId: MsgId, to: String, newStatusCode: String, statusDetail: Option[String]) extends LegacyConnectingSignal


trait DEPRECATED_HasWallet {
  def ctx: ProtocolContextApi[_,_,_,_,_,_]

  def appConfig: AppConfig
  def walletAPI: WalletAPI

  var walletId: String = _
  implicit lazy val wap: WalletAPIParam = WalletAPIParam(walletId)

  def initWalletDetail(seed: String): Unit = walletId = seed

  protected def prepareNewAgentWalletData(forDIDPair: DidPair, walletId: String): NewKeyCreated = {
    val agentWAP = WalletAPIParam(walletId)
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[WalletCreated.type](CreateWallet())(agentWAP))
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[TheirKeyStored](StoreTheirKey(forDIDPair.did, forDIDPair.verKey))(agentWAP))
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[NewKeyCreated](CreateNewKey())(agentWAP))
  }

  def getVerKeyReqViaCache(did: DidStr, getKeyFromPool: Boolean = false): GetVerKeyResp =
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[GetVerKeyResp](GetVerKey(did, getKeyFromPool)))

}
