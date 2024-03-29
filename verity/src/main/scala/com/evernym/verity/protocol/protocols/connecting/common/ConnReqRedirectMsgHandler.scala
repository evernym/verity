package com.evernym.verity.protocol.protocols.connecting.common

import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.{MSG_STATUS_REDIRECTED, REDIRECTED_CONN_REQ_EXISTS}
import com.evernym.verity.actor.{AgentKeyDlgProofSet, ConnectionStatusUpdated, Evt, MsgAnswered, MsgCreated, MsgDetailAdded, MsgPayloadStored, TheirDidDocDetail}
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK, MPF_PLAIN, Unrecognized}
import com.evernym.verity.actor.wallet.{PackedMsg, StoreTheirKey, TheirKeyStored}
import com.evernym.verity.agentmsg.msgfamily.AgentMsgContext
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.CREATE_MSG_TYPE_CONN_REQ
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, PackMsgParam}
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.protocol.engine.Protocol
import com.evernym.verity.util.TimeZoneUtil.getMillisForCurrentUTCZonedDateTime
import org.json.JSONObject


trait ConnReqRedirectMsgHandler[S <: ConnectingStateBase[S]] {
  this: ConnectingProtocolBase[_,_,S,_] with Protocol[_,_,ProtoMsg,Any,S,_] =>

  protected def handleRedirectConnReqMsgBase(rcrm: RedirectConnReqMsg)(implicit amc: AgentMsgContext): PackedMsg = {
    validateRedirectConnReqMsg(rcrm)
    persistAndProcessRedirectConnReqMsg(rcrm)
    processPersistedConnReqRedirectMsg(rcrm)
  }

  private def validateRedirectConnReqMsg(rcrm: RedirectConnReqMsg)
                                        (implicit agentMsgContext: AgentMsgContext): Unit = {
    checkNoRedirectedInvitationExists()
    checkIfReplyMsgIdProvided(rcrm.replyToMsgId)
    checkConnReqMsgIfExistsNotExpired(rcrm.replyToMsgId)
    ctx.getState.checkIfMsgAlreadyNotInAnsweredState(rcrm.id)
  }

  def persistAndProcessRedirectConnReqMsg(rcrm: RedirectConnReqMsg)(implicit agentMsgContext: AgentMsgContext): Unit = {

    def prepareEdgePayloadStoredEventOpt(answerMsgUid: MsgId): Option[MsgPayloadStored] = {
      if (rcrm.keyDlgProof.isEmpty) {
        val (msgName, payload) = ConnectingMsgHelper.buildRedirectPayloadMsg(
          agentMsgContext.msgPackFormat, rcrm.senderDetail, rcrm.redirectDetail.toString)
        prepareEdgePayloadStoredEvent(answerMsgUid, msgName, payload)
      } else None
    }

    /**
     * prepares connection request
     * @return
     */
    def prepareInviteAnswerConnReqCreatedEventOpt: Option[MsgCreated] = {
      Option(rcrm.replyToMsgId).flatMap { replyToMsgId =>
        val reqMsg = ctx.getState.connectingMsgState.getMsgOpt(replyToMsgId)
        if (reqMsg.isEmpty) {
          Option (buildMsgCreatedEvt (rcrm.replyToMsgId, CREATE_MSG_TYPE_CONN_REQ,
            rcrm.senderDetail.DID, sendMsg=false, rcrm.threadOpt))
        } else None
      }
    }

    def prepareMsgCreatedEvent: MsgCreated = {
      val answerMsgSenderDID = if (rcrm.keyDlgProof.isDefined) ctx.getState.myPairwiseDIDReq
      else rcrm.senderDetail.DID
      buildMsgCreatedEvt (rcrm.id, rcrm.msgFamilyDetail.msgName, answerMsgSenderDID,
         rcrm.sendMsg, rcrm.threadOpt).
        copy (statusCode = MSG_STATUS_REDIRECTED.statusCode)
    }

    def prepareConnReqMsgAnsweredEvent(answerMsgUid: MsgId): Option[MsgAnswered] = {
      Option(rcrm.replyToMsgId).map { replyToMsgId =>
        MsgAnswered (replyToMsgId, MSG_STATUS_REDIRECTED.statusCode, answerMsgUid, getMillisForCurrentUTCZonedDateTime)
      }
    }

    def prepareAgentKeyDlgProofSetEventOpt: Option[AgentKeyDlgProofSet] = {
      rcrm.keyDlgProof.map { lkdp =>
        AgentKeyDlgProofSet (lkdp.agentDID, lkdp.agentDelegatedKey, lkdp.signature)
      }
    }

    def writeConnReqAnswerMsgDetail(uid: MsgId, rcrm: RedirectConnReqMsg): Unit = {
      ctx.apply(MsgDetailAdded(uid, REDIRECT_DETAIL, rcrm.redirectDetail.toString))
    }

    val connReqMsgCreatedEventOpt: Option[MsgCreated] = prepareInviteAnswerConnReqCreatedEventOpt
    val answerMsgCreatedEvent = prepareMsgCreatedEvent
    val answerMsgEdgePayloadStoredEventOpt = prepareEdgePayloadStoredEventOpt(answerMsgCreatedEvent.uid)
    val connReqMsgAnsweredEventOpt = prepareConnReqMsgAnsweredEvent(answerMsgCreatedEvent.uid)
    val agentKeyDlgProofSetEventOpt = prepareAgentKeyDlgProofSetEventOpt

    connReqMsgCreatedEventOpt.foreach(ctx.apply)
    ctx.apply(answerMsgCreatedEvent)
    answerMsgEdgePayloadStoredEventOpt.foreach(ctx.apply)

    writeConnReqAnswerMsgDetail (answerMsgCreatedEvent.uid, rcrm)
    connReqMsgAnsweredEventOpt.foreach(ctx.apply)
    val connReqSenderAgentKeyDlgProof = rcrm.senderDetail.agentKeyDlgProof

    agentKeyDlgProofSetEventOpt.foreach(ctx.apply)

    val theirDidDocDetailOpt = connReqSenderAgentKeyDlgProof.map { rkdp =>
      TheirDidDocDetail(
        rcrm.senderDetail.DID,
        rcrm.senderAgencyDetail.DID,
        rkdp.agentDID,
        rkdp.agentDelegatedKey,
        rkdp.signature)
    }

    ctx.apply(ConnectionStatusUpdated(reqReceived = true, MSG_STATUS_REDIRECTED.statusCode, theirDidDocDetailOpt))

    theirDidDocDetailOpt.foreach { _ =>
      ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[TheirKeyStored](
        StoreTheirKey(rcrm.senderAgencyDetail.DID,
          rcrm.senderAgencyDetail.verKey, ignoreIfAlreadyExists = true)))

      ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[TheirKeyStored](
        StoreTheirKey(rcrm.senderDetail.DID,
          rcrm.senderDetail.verKey, ignoreIfAlreadyExists = true)))
    }

    connReqSenderAgentKeyDlgProof.foreach { rkdp =>
      ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[TheirKeyStored](
        StoreTheirKey(rkdp.agentDID, rkdp.agentDelegatedKey, ignoreIfAlreadyExists = true))
      )
    }

    //NOTE: below are signal messages to be sent to agent actor to be stored/updated in agent's message store
    // because get/download message API only queries agent's message store
    connReqMsgCreatedEventOpt match {
      case Some(event) =>
        DEPRECATED_sendSpecialSignal(AddMsg(event.copy(refMsgId = answerMsgCreatedEvent.uid, statusCode = MSG_STATUS_REDIRECTED.statusCode)))
      case None =>
        connReqMsgAnsweredEventOpt.foreach { ae =>
          DEPRECATED_sendSpecialSignal(UpdateMsg(ae.uid, ae.statusCode, Evt.getOptionFromValue(ae.refMsgId)))
        }
    }
    DEPRECATED_sendSpecialSignal(AddMsg(answerMsgCreatedEvent, answerMsgEdgePayloadStoredEventOpt.map(_.payload.toByteArray)))

  }

  private def processPersistedConnReqRedirectMsg( rcrm: RedirectConnReqMsg)
                                                 (implicit agentMsgContext: AgentMsgContext): PackedMsg = {
    val otherRespMsgs = buildSendMsgResp(rcrm.id)
    val redirectRespMsg = RedirectConnReqMsgHelper.buildRespMsg(rcrm.id, threadIdReq, getSourceIdFor(rcrm.replyToMsgId))
    if (rcrm.keyDlgProof.isEmpty) {
      agentMsgContext.msgPackFormat match {
        case MPF_INDY_PACK | MPF_PLAIN =>
          ctx.signal(RedirectedInviteAnswerMsg_0_6(RedirectPayloadMsg_0_6(rcrm.senderDetail, new JSONObject(rcrm.redirectDetail.toString))))
        case MPF_MSG_PACK =>
        case Unrecognized(_) => throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
      }
    }
    val respMsgs = redirectRespMsg ++ otherRespMsgs
    val param: PackMsgParam = AgentMsgPackagingUtil.buildPackMsgParam(
      encParamBasedOnMsgSender(agentMsgContext.senderVerKey),
      respMsgs, agentMsgContext.wrapInBundledMsg)
    buildAgentPackedMsg(agentMsgContext.msgPackFormat, param)
  }

  def checkNoRedirectedInvitationExists(): Unit = {
    if (ctx.getState.state.connectionStatus.exists(_.answerStatusCode == MSG_STATUS_REDIRECTED.statusCode)) {
      throw new BadRequestErrorException(REDIRECTED_CONN_REQ_EXISTS.statusCode)
    }
  }
}
