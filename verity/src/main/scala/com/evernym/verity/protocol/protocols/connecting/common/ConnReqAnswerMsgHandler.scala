package com.evernym.verity.protocol.protocols.connecting.common

import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.{INVALID_VALUE, MISSING_REQ_FIELD, MSG_STATUS_ACCEPTED, MSG_STATUS_REDIRECTED, MSG_STATUS_REJECTED, PAIRWISE_KEYS_ALREADY_IN_WALLET}
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK, MPF_PLAIN, Unrecognized}
import com.evernym.verity.actor.agent.user.MsgHelper
import com.evernym.verity.actor.wallet.{GetVerKeyOpt, GetVerKeyOptResp, PackedMsg, StoreTheirKey, TheirKeyStored}
import com.evernym.verity.agentmsg.msgfamily.AgentMsgContext
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.CREATE_MSG_TYPE_CONN_REQ
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, PackMsgParam}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.container.actor.ProtoMsg
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.TimeZoneUtil.getMillisForCurrentUTCZonedDateTime


/**
 * handles different types of responses to connection requests
 * for example: accept, reject
 * @tparam S state type
 */
trait ConnReqAnswerMsgHandler[S <: ConnectingStateBase[S]] {
  this: ConnectingProtocolBase[_,_,S,_]
  with Protocol[_,_,ProtoMsg,Any,S,_] =>

  protected def handleConnReqAnswerMsgBase(connReqAnswerMsg: ConnReqAnswerMsg)(implicit amc: AgentMsgContext): PackedMsg = {
    validateConnReqAnswerMsg(connReqAnswerMsg)
    persistAndProcessConnReqAnswerMsg(connReqAnswerMsg)
    processPersistedConnReqAnswerMsg(connReqAnswerMsg)
  }

  private def validateConnReqAnswerMsg(connReqAnswerMsg: ConnReqAnswerMsg)
                                      (implicit agentMsgContext: AgentMsgContext): Unit = {
    checkNoAcceptedInvitationExists()
    checkIfReplyMsgIdProvided(connReqAnswerMsg.replyToMsgId)
    checkConnReqMsgIfExistsNotExpired(connReqAnswerMsg.replyToMsgId)
    ctx.getState.checkIfMsgAlreadyNotInAnsweredState(connReqAnswerMsg.id)
    checkIfValidAnswerStatusCode(connReqAnswerMsg.answerStatusCode)
    connReqAnswerMsg.senderDetail.agentKeyDlgProof.map(_.agentDID).foreach(checkSenderKeyNotAlreadyUsed)
    if (connReqAnswerMsg.answerStatusCode == MSG_STATUS_ACCEPTED.statusCode) {
      checkIfSentByEdgeAndAgentKeyDlgProofNotEmpty(agentMsgContext.senderVerKey, connReqAnswerMsg.keyDlgProof)
    }
    connReqAnswerMsg.keyDlgProof.foreach(kdp => verifyAgentKeyDlgProof(kdp,
      myPairwiseVerKeyReq, isEdgeAgentsKeyDlgProof = true))
    connReqAnswerMsg.senderDetail.agentKeyDlgProof.foreach(verifyAgentKeyDlgProof(_,
      connReqAnswerMsg.senderDetail.verKey, isEdgeAgentsKeyDlgProof = false))
  }

  //TODO: long method, need to break it
  private def persistAndProcessConnReqAnswerMsg(connReqAnswerMsg: ConnReqAnswerMsg)
                                               (implicit agentMsgContext: AgentMsgContext): Unit = {

    def prepareEdgePayloadStoredEventOpt(answerMsgUid: MsgId): Option[MsgPayloadStored] = {
      if (connReqAnswerMsg.keyDlgProof.isEmpty) {
        val sourceId = getSourceIdFor(connReqAnswerMsg.replyToMsgId)
        val (msgName, externalPayloadMsg) = ConnectingMsgHelper.buildInviteAnswerPayloadMsg(
          agentMsgContext.msgPackFormat, connReqAnswerMsg, sourceId)
        prepareEdgePayloadStoredEvent(answerMsgUid, msgName, externalPayloadMsg)
      } else {
        None
      }
    }

    def prepareInviteAnswerConnReqCreatedEventOpt: Option[MsgCreated] = {
      Option(connReqAnswerMsg.replyToMsgId).flatMap { replyToMsgId =>
        val reqMsg = ctx.getState.connectingMsgState.getMsgOpt(replyToMsgId)
        if (reqMsg.isEmpty) {
          Option (
            buildMsgCreatedEvt(
              connReqAnswerMsg.replyToMsgId,
              CREATE_MSG_TYPE_CONN_REQ,
              connReqAnswerMsg.senderDetail.DID,
              sendMsg=false,
              connReqAnswerMsg.threadOpt
            )
          )
        } else None
      }
    }

    def prepareMsgCreatedEvent: MsgCreated = {
      val answerMsgSenderDID = if (connReqAnswerMsg.keyDlgProof.isDefined) ctx.getState.myPairwiseDIDReq
      else connReqAnswerMsg.senderDetail.DID
      buildMsgCreatedEvt(connReqAnswerMsg.id,
        connReqAnswerMsg.msgFamilyDetail.msgName, answerMsgSenderDID,
        connReqAnswerMsg.sendMsg, connReqAnswerMsg.threadOpt
      ).copy (statusCode = connReqAnswerMsg.answerStatusCode)
    }

    def prepareConnReqMsgAnsweredEvent(answerMsgUid: MsgId): Option[MsgAnswered] = {
      Option(connReqAnswerMsg.replyToMsgId).map { replyToMsgId =>
        MsgAnswered (replyToMsgId, connReqAnswerMsg.answerStatusCode, answerMsgUid, getMillisForCurrentUTCZonedDateTime)
      }
    }

    def prepareAgentKeyDlgProofSetEventOpt: Option[AgentKeyDlgProofSet] = {
      connReqAnswerMsg.keyDlgProof.map { lkdp =>
        AgentKeyDlgProofSet (lkdp.agentDID, lkdp.agentDelegatedKey, lkdp.signature)
      }
    }

    val connReqMsgCreatedEventOpt: Option[MsgCreated] = prepareInviteAnswerConnReqCreatedEventOpt
    val answerMsgCreatedEvent = prepareMsgCreatedEvent
    val answerMsgEdgePayloadStoredEventOpt = prepareEdgePayloadStoredEventOpt(answerMsgCreatedEvent.uid)
    val connReqMsgAnsweredEventOpt = prepareConnReqMsgAnsweredEvent(answerMsgCreatedEvent.uid)
    val agentKeyDlgProofSetEventOpt = prepareAgentKeyDlgProofSetEventOpt
    connReqMsgCreatedEventOpt.foreach(ctx.apply)
    ctx.apply(answerMsgCreatedEvent)
    answerMsgEdgePayloadStoredEventOpt.foreach (ctx.apply)
    connReqMsgAnsweredEventOpt.foreach (ctx.apply)

    val connReqSenderAgentKeyDlgProof = connReqAnswerMsg.senderDetail.agentKeyDlgProof
    agentKeyDlgProofSetEventOpt.foreach(ctx.apply)

    val theirDidDocDetailOpt = connReqSenderAgentKeyDlgProof.map { rkdp =>
      TheirDidDocDetail(
        connReqAnswerMsg.senderDetail.DID,
        connReqAnswerMsg.senderAgencyDetail.DID,
        rkdp.agentDID,
        rkdp.agentDelegatedKey,
        rkdp.signature,
        connReqAnswerMsg.senderDetail.verKey
      )
    }

    ctx.apply(ConnectionStatusUpdated(reqReceived = true, connReqAnswerMsg.answerStatusCode, theirDidDocDetailOpt))

    theirDidDocDetailOpt.foreach { _ =>
      ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[TheirKeyStored](
        StoreTheirKey(connReqAnswerMsg.senderAgencyDetail.DID,
          connReqAnswerMsg.senderAgencyDetail.verKey, ignoreIfAlreadyExists = true)))

      ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[TheirKeyStored](
        StoreTheirKey(connReqAnswerMsg.senderDetail.DID,
          connReqAnswerMsg.senderDetail.verKey, ignoreIfAlreadyExists = true)))
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
        DEPRECATED_sendSpecialSignal(AddMsg(event.copy(refMsgId = answerMsgCreatedEvent.uid, statusCode = connReqAnswerMsg.answerStatusCode)))
      case None =>
        connReqMsgAnsweredEventOpt.foreach { ae =>
          DEPRECATED_sendSpecialSignal(UpdateMsg(ae.uid, ae.statusCode, Evt.getOptionFromValue(ae.refMsgId)))
        }
    }
    DEPRECATED_sendSpecialSignal(AddMsg(answerMsgCreatedEvent, answerMsgEdgePayloadStoredEventOpt.map(_.payload.toByteArray)))

  }

  private def processPersistedConnReqAnswerMsg(connReqAnswerMsg: ConnReqAnswerMsg)
                                              (implicit agentMsgContext: AgentMsgContext): PackedMsg = {
    val otherRespMsgs = buildSendMsgResp(connReqAnswerMsg.id)
    val sourceId = getSourceIdFor(connReqAnswerMsg.replyToMsgId)
    val inviteAnsweredRespMsg =
      connReqAnswerMsg.answerStatusCode match {
        case MSG_STATUS_ACCEPTED.statusCode =>
          updateParentStateAfterConnReqAnswerMsgHandled()
          AcceptConnReqMsgHelper.buildRespMsg(connReqAnswerMsg.id, threadIdReq, sourceId)
        case MSG_STATUS_REJECTED.statusCode =>
          DeclineConnReqMsgHelper.buildRespMsg(connReqAnswerMsg.id, threadIdReq, sourceId)
        case MSG_STATUS_REDIRECTED.statusCode =>
          RedirectConnReqMsgHelper.buildRespMsg(connReqAnswerMsg.id, threadIdReq, sourceId)
      }
    if (connReqAnswerMsg.keyDlgProof.isEmpty) {
      agentMsgContext.msgPackFormat match {
        case MPF_INDY_PACK | MPF_PLAIN =>
          ctx.signal(AcceptedInviteAnswerMsg_0_6(InviteAnswerPayloadMsg(connReqAnswerMsg.senderDetail), sourceId))
        case MPF_MSG_PACK =>
        case Unrecognized(_) => throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
      }
    }
    val param: PackMsgParam = AgentMsgPackagingUtil.buildPackMsgParam(
      encParamBasedOnMsgSender(agentMsgContext.senderVerKey),
      inviteAnsweredRespMsg ++ otherRespMsgs, agentMsgContext.wrapInBundledMsg)
    buildAgentPackedMsg(agentMsgContext.msgPackFormatToBeUsed, param)
  }

  private def updateParentStateAfterConnReqAnswerMsgHandled(): Unit = {
    ctx.getState.state.connectionStatus.zip(ctx.getState.state.theirDIDDoc).foreach { case (cs, tdd) =>
      tdd.legacyRoutingDetail.foreach { rd =>
        val cc = ConnectionStatusUpdated(cs.reqReceived, cs.answerStatusCode, Option(
          TheirDidDocDetail(tdd.DID.getOrElse(""), rd.agencyDID,
            rd.agentKeyDID, rd.agentVerKey, rd.agentKeyDlgProofSignature)))
        ctx.signal(cc)
      }
    }
  }

  private def checkSenderKeyNotAlreadyUsed(senderDID: DidStr): Unit = {
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[GetVerKeyOptResp](GetVerKeyOpt(senderDID))).verKey foreach { _ =>
      throw new BadRequestErrorException(PAIRWISE_KEYS_ALREADY_IN_WALLET.statusCode, Option("pairwise keys already " +
        s"in wallet for did: $senderDID"))
    }
  }

  private def checkIfValidAnswerStatusCode(statusCode: String): Unit = {
    if (! MsgHelper.validAnsweredMsgStatuses.contains(statusCode)) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("invalid answer status code: " + statusCode))
    }
  }

  def checkIfSentByEdgeAndAgentKeyDlgProofNotEmpty(senderVerKey: Option[VerKeyStr], keyDlgProof: Option[AgentKeyDlgProof])
  : Unit = {
    if (senderVerKey.isDefined && isUserPairwiseVerKey(senderVerKey.getOrElse("")) && keyDlgProof.isEmpty ) {
      throw new BadRequestErrorException(MISSING_REQ_FIELD.statusCode, Option("missing required attribute: 'keyDlgProof'"))
    }
  }
}
