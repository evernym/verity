package com.evernym.verity.actor.agent.msghandler.outgoing

import java.util.UUID

import akka.actor.ActorRef
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{MSG_DELIVERY_STATUS_FAILED, MSG_DELIVERY_STATUS_SENT}
import com.evernym.verity.actor.ProtoMsgSenderOrderIncremented
import com.evernym.verity.actor.agent.{AgentIdentity, HasAgentActivity, MsgPackVersion, ThreadContextDetail, TypeFormat}
import com.evernym.verity.actor.agent.MsgPackVersion.{MPV_INDY_PACK, MPV_MSG_PACK, MPV_PLAIN}
import com.evernym.verity.actor.agent.msghandler.{AgentMsgHandler, MsgRespContext}
import com.evernym.verity.actor.msg_tracer.progress_tracker.MsgParam
import com.evernym.verity.actor.persistence.{AgentPersistentActor, Done}
import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.agentmsg.msgcodec.AgentJsonMsg
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.agentmsg.msgpacker.AgentMsgPackagingUtil
import com.evernym.verity.constants.Constants.UNKNOWN_SENDER_PARTICIPANT_ID
import com.evernym.verity.msg_tracer.MsgTraceProvider._
import com.evernym.verity.protocol.actor.ServiceDecorator
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols
import com.evernym.verity.actor.agent.PayloadMetadata
import com.evernym.verity.protocol.protocols.connecting.v_0_6.{ConnectingProtoDef => ConnectingProtoDef_v_0_6}
import com.evernym.verity.protocol.protocols.tokenizer.TokenizerMsgFamily.PushToken
import com.evernym.verity.push_notification.{PushNotifData, PushNotifResponse}
import com.evernym.verity.util.{ParticipantUtil, ReqMsgContext}
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyInfo}

import scala.util.{Failure, Success, Try}


trait AgentOutgoingMsgHandler
  extends SendOutgoingMsg
    with AgentIdentity
    with HasAgentActivity { this: AgentMsgHandler with AgentPersistentActor =>

  lazy val defaultSelfRecipKeys = Set(KeyInfo(Right(GetVerKeyByDIDParam(domainId, getKeyFromPool = false))))

  def agentOutgoingCommonCmdReceiver[A]: Receive = {

    //[LEGACY] pinst -> actor protocol container (sendRespToCaller method) -> this actor
    case psrp: ProtocolSyncRespMsg      => handleProtocolSyncRespMsg(psrp)

    //pinst -> actor protocol container (send method) -> this actor
    case ProtocolOutgoingMsg(sd: ServiceDecorator[A], to, _, rmId, tId, pinstId, pDef)
                                        => handleProtocolServiceDecorator(sd, to, rmId, tId, pDef, pinstId)

    //pinst -> actor protocol container (send method) -> this actor
    case pom: ProtocolOutgoingMsg[A]    => handleProtocolOutgoingMsg(pom)

    //pinst -> actor driver (sendToForwarder method) -> this actor
    case ssm: SendSignalMsg[A]          => handleSendSignalMsg(ssm)

    //this actor -> this actor (after done some pre processing work)
    case pssm: ProcessSendSignalMsg[A]  => processSendSignalMsg(pssm.ssm)

    //this actor -> this actor
    case ssm: SendStoredMsgToSelf       => handleSendStoredMsgToSelf(ssm.msgId)
  }

  /**
   * this is only for legacy agent messages which used to expect synchronous responses
   * @param psrm protocol synchronous response message
   */
  def handleProtocolSyncRespMsg(psrm: ProtocolSyncRespMsg): Unit = {
    psrm.requestMsgId.foreach { requestMsgId =>
      msgRespContext.get(requestMsgId).flatMap(_.senderActorRef).foreach { senderActorRef =>
        sendMsgToWaitingCaller(psrm.msg, requestMsgId, senderActorRef)
      }
    }
  }

  /**
   * this is used when protocol container 'send msg api' sends 'ProtocolOutgoingMsg' to this agent actor
   * @param pom protocol outgoing message
   * @tparam A
   */
  def handleProtocolOutgoingMsg[A](pom: ProtocolOutgoingMsg[A]): Unit = {
    logger.trace(s"sending protocol outgoing message: $pom")
    handleOutgoingMsg(OutgoingMsg(pom.msg, pom.to, pom.from, pom.threadId, pom.pinstId,
      pom.protoDef, Option(pom.requestMsgId)))
  }

  def handleProtocolServiceDecorator[A](sd: ServiceDecorator[A],
                                        to: ParticipantId,
                                        requestMsgId: MsgId,
                                        threadId: ThreadId,
                                        protoDef: ProtoDef,
                                        pinstId: PinstId): Unit = {
    val agentMsg: AgentJsonMsg = createAgentMsg(sd.msg, threadId, pinstId, protoDef, Option(TypeFormat.STANDARD_TYPE_FORMAT))

      sd match {
      case pushToken: PushToken =>
        val future = sendPushNotif(
          Set(sd.deliveryMethod),
          //TODO: do we want to use requestMsgId here or a new msg id?
          PushNotifData(requestMsgId, agentMsg.msgType.msgName, sendAsAlertPushNotif = true, Map.empty,
            Map("type" -> agentMsg.msgType.msgName, "msg" -> agentMsg.jsonStr)),
          Some(pushToken.msg.sponsorId)
        )
        future.map {
          case pnds: PushNotifResponse if MSG_DELIVERY_STATUS_SENT.hasStatusCode(pnds.statusCode) =>
            logger.trace(s"push notification sent successfully: $pnds")
          case pnds: PushNotifResponse if MSG_DELIVERY_STATUS_FAILED.hasStatusCode(pnds.statusCode) =>
            //TODO: How do we communicate a failed response? Change Actor state?
            logger.error(s"push notification failed (participantId: $to): $pnds")
          case x =>
            //TODO: How do we communicate a failed response? Change Actor state?
            logger.error(s"push notification failed (participantId: $to): $x")
        }
      case x => throw new RuntimeException("unsupported Service Decorator: " + x)
    }
  }

  /**
   * this is send by actor driver (who handles outgoing signal messages)
   * @param ssm send signal message
   * @tparam A
   */
  def processSendSignalMsg[A](ssm: SendSignalMsg[A]): Unit = {
    logger.trace(s"sending signal msg to endpoint: $ssm")
    val outMsg = OutgoingMsg(
      msg = ssm.msg,
      to = ParticipantUtil.participantId(state.thisAgentKeyDIDReq, Option(domainId)), //assumption, signal msgs are always sent to domain id participant
      from = selfParticipantId,   //assumption, signal msgs are always sent from self participant id
      threadId = ssm.threadId,
      pinstId = ssm.pinstId,
      protoDef = protocols.protoDef(ssm.protoRef),
      requestMsgId = ssm.requestMsgId
    )
    handleOutgoingMsg(outMsg, isSignalMsg = true)
  }

  def handleOutgoingMsg[A](oam: OutgoingMsg[A], isSignalMsg: Boolean=false): Unit = {
    MsgProgressTracker.recordOutMsgPackagingStarted(
      inMsgParam = MsgParam(msgId = oam.context.requestMsgId))

    logger.debug(s"[$persistenceId] preparing outgoing agent message: $oam")

    logger.debug(s"outgoing msg: native msg : " + oam.msg)

    val threadContext = getThreadContext(oam.pinstId)
    logger.debug("outgoing msg: thread context to be used: " + threadContext)

    val agentMsg = createAgentMsg(oam.msg, oam.threadId, oam.pinstId, oam.protoDef, isSignalMsg=isSignalMsg)
    logger.debug("outgoing msg: prepared agent msg: " + threadContext)

    if (!isSignalMsg) {
      val myDID = ParticipantUtil.agentId(oam.context.from)
      logger.debug(s"outgoing msg: my participant DID: " + myDID)
      AgentActivityTracker.track(agentMsg.msgType.msgName, domainId, state.sponsorRel, Some(myDID))
    }

    handleOutgoingMsg(agentMsg, threadContext, oam.context)
  }

  def createAgentMsg(msg: Any, threadId: ThreadId, pinstId: PinstId,
                     protoDef: ProtoDef, msgTypeFormat: Option[TypeFormat]=None,
                     isSignalMsg: Boolean=false): AgentJsonMsg = {

    if (! isSignalMsg) {
      val event = ProtoMsgSenderOrderIncremented(pinstId)
      receiveRecover(event)
      writeWithoutApply(event)
    }

    def getNewMsgId: MsgId = UUID.randomUUID().toString

    val threadContextResult = Try(getThreadContext(pinstId))
    val (msgId, mtf, protoMsgDetail) = threadContextResult match {
      case Success(tcd)                       =>
        val mId = if (tcd.protoMsgOrderDetail.exists(_.senderOrder == 0)
          && tcd.protoMsgOrderDetail.exists(_.receivedOrders.isEmpty) ){
          //this is temporary workaround to solve an issue between how
          // thread id is determined by libvcx (and may be by other third parties) vs verity/agency
          // here, we are basically checking if this msg is 'first' protocol msg and in that case
          // the @id of the msg is assigned the thread id itself
          tcd.threadId
        } else {
          getNewMsgId
        }
        (mId, msgTypeFormat.getOrElse(tcd.msgTypeFormat), tcd.protoMsgOrderDetail)
      case Failure(_: NoSuchElementException) =>
        (getNewMsgId, msgTypeFormat.get, None)
      case Failure(e) => throw e // TODO ryan please check that this is correct
    }

    //need to find better way to handle this
    //during connections protocol, when first message 'request' is received from other side,
    //that participant is unknown and hence it is stored as 'unknown_sender_participant_id' in the thread context
    //and when it responds with 'response' message, it just adds that in thread object
    //but for recipient it may look unfamilier and for now, filtering it.
    val updatedPmd = protoMsgDetail.map { pmd =>
      pmd.copy(receivedOrders = pmd.receivedOrders.filter(_._1 != UNKNOWN_SENDER_PARTICIPANT_ID))
    }
    buildAgentMsg(msg, msgId, threadId, protoDef, mtf, updatedPmd)
  }


  /**
   * handles outgoing message processing
   * @param agentJsonMsg agent json message to be sent
   * @param threadContext thread context to be used during packaging
   * @param mc: message context (msgId, to and from)
   * @tparam A
   */
  def handleOutgoingMsg[A](agentJsonMsg: AgentJsonMsg, threadContext: ThreadContextDetail, mc: OutgoingMsgContext): Unit = {
    val agentJsonStr = if (threadContext.usesLegacyGenMsgWrapper) {
      AgentMsgPackagingUtil.buildPayloadWrapperMsg(agentJsonMsg.jsonStr, wrapperMsgType = agentJsonMsg.msgType.msgName)
    } else {
      AgentMsgPackagingUtil.buildAgentMsgJson(List(JsonMsg(agentJsonMsg.jsonStr)), threadContext.msgPackVersion, threadContext.usesLegacyBundledMsgWrapper)
    }
    logger.debug(s"outgoing msg: json msg: " + agentJsonMsg)
    val toDID = ParticipantUtil.agentId(mc.to)
    logger.debug(s"outgoing msg: to participant DID: " + toDID)
    logger.debug(s"outgoing msg: final agent msg: " + agentJsonStr)

    // msg is sent as PLAIN json. Packing is done later if needed.
    val omp = OutgoingMsgParam(JsonMsg(agentJsonStr), Option(PayloadMetadata(agentJsonMsg.msgType, MPV_PLAIN)))
    sendToWaitingCallerOrSendToNextHop(omp, agentJsonMsg.msgType, mc, threadContext)
  }

  def getThreadContext(pinstId: PinstId): ThreadContextDetail = {
    val threadContext = state.threadContextDetail(pinstId)
    logger.debug("stored threadContext: " + threadContext)
    threadContext
  }

  /**
   * once outgoing message is packed and ready,
   * it checks
   *   if there is a caller waiting for this response
   *        (which is true for legacy 0.5 version of messages or any other expecting a synchronous response)
   *   else sends it to appropriate agent (edge agent or to edge/cloud agent of the given connection)
   * @param omp
   * @param msgType
   * @param mc
   * @param threadContext
   */
  private def sendToWaitingCallerOrSendToNextHop(omp: OutgoingMsgParam,
                                                 msgType: MsgType,
                                                 mc: OutgoingMsgContext,
                                                 threadContext: ThreadContextDetail): Unit = {

    val respMsgId = getNewMsgId
    //tracking related
      mc.requestMsgId.foreach(updateAsyncReqContext(_, respMsgId, Option(msgType.msgName)))
      MsgProgressTracker.recordOutMsgPackagingFinished(
        inMsgParam = MsgParam(msgId = mc.requestMsgId),
        outMsgParam = MsgParam(msgId = Option(respMsgId), msgName = Option(msgType.msgName)))
    //tracking related

    mc.requestMsgId.map(reqMsgId => (reqMsgId, msgRespContext.get(reqMsgId))) match {
      case Some((rmid, Some(MsgRespContext(_, packForVerKey, Some(sar))))) =>
        // pack the message if needed.
        val updatedOmp = threadContext.msgPackVersion match {
          case MPV_PLAIN => omp
          case MPV_INDY_PACK | MPV_MSG_PACK =>
            // we pack the message if needed.
            packOutgoingMsg(omp, mc.to, threadContext.msgPackVersion, packForVerKey.map(svk => KeyInfo(Left(svk))))
        }
        logger.debug(s"outgoing msg will be sent to waiting caller...")
        sendMsgToWaitingCaller(updatedOmp, rmid, sar)
      case Some((_, _))  =>
        processOutgoingMsg(omp, respMsgId, msgType, mc, threadContext)
      case None => ???
    }
  }

  def processOutgoingMsg(omp: OutgoingMsgParam,
                         msgId: MsgId,
                         msgType: MsgType,
                         mc: OutgoingMsgContext,
                         threadContext: ThreadContextDetail): Unit = {
    val thread = Option(Thread(Option(threadContext.threadId)))
    logger.debug("sending outgoing msg => self participant id: " + selfParticipantId)
    logger.debug("sending outgoing => toParticipantId: " + mc.to)
    val (sendResult, nextHop) = if (ParticipantUtil.DID(selfParticipantId) == ParticipantUtil.DID(mc.to)) {
      msgType match {

        // These signals should not be stored because of legacy reasons.
        case mt: MsgType if isLegacySignalMsgNotToBeStored(mt) =>
          (sendUnstoredMsgToEdge(omp), NEXT_HOP_MY_EDGE_AGENT)

        // Other signals go regularly.
        case _ =>
          storeOutgoingMsg(omp, msgId, msgType.msgName, ParticipantUtil.DID(mc.from), thread)
          (sendStoredMsgToEdge(msgId), NEXT_HOP_MY_EDGE_AGENT)
      }
    } else {
      // between cloud agents, we don't support sending MPV_PLAIN messages, so default to MPV_INDY_PACK in that case
      val msgPackVersion =  threadContext.msgPackVersion match {
        case MPV_PLAIN => MPV_INDY_PACK
        case other => other
      }

      // pack the message
      val updatedOmp = packOutgoingMsg(omp, mc.to, msgPackVersion)
      logger.debug(s"outgoing msg will be stored and sent ...")
      storeOutgoingMsg(updatedOmp, msgId, msgType.msgName, ParticipantUtil.DID(mc.from), thread)
      val sendResult = sendMsgToOtherEntity(updatedOmp, msgId, msgType.msgName, thread)
      (sendResult, NEXT_HOP_THEIR_ROUTING_SERVICE)
    }
    MsgTracerProvider.recordMetricsForAsyncRespMsgId(msgId, nextHop)   //tracing related
    withRespMsgId(msgId, { arc =>
      sendResult.onComplete {
        case Success(_) => MsgProgressTracker.recordMsgSentToNextHop(nextHop, arc)
        case Failure(e) => MsgProgressTracker.recordMsgSendingFailed(nextHop, e.getMessage, arc)
      }
    })
  }

  private def sendMsgToWaitingCaller(om: Any, reqMsgId: MsgId, sar: ActorRef): Unit = {
    msgRespContext = msgRespContext.filterNot(_._1 == reqMsgId)
    val msg = om match {
      case OutgoingMsgParam(om, _)   => om
      case other                     => other
    }
    sar ! msg
    MsgTracerProvider.recordMetricsForAsyncReqMsgId(reqMsgId, NEXT_HOP_MY_EDGE_AGENT_SYNC)   //tracing related
    withReqMsgId(reqMsgId, { arc =>
      MsgProgressTracker.recordMsgSentToNextHop(NEXT_HOP_MY_EDGE_AGENT_SYNC, arc)
    })
  }

  def handleSendStoredMsgToSelf(uid: MsgId): Unit = {
    val sndr = sender()
    sendStoredMsgToSelf(uid) map { _ =>
      sndr ! Done
    }
  }


  /**
   * this is default handler for 'SendSignalMsg' command
   * it can be overridden for any special use case
   * (like it is overridden in user agent actor to handle special case around connection)
   * @param ssm
   * @tparam A
   */
  def handleSendSignalMsg[A](ssm: SendSignalMsg[A]): Unit = {
    processSendSignalMsg(ssm)
  }

  /**
   * this is a workaround to handle scenarios with legacy messages where we do wanted to send response messages
   * as a signal message but those are already stored so we wanted to avoid re-storing them again.
   * once we move to newer versions of connecting protocols and deprecate support of these legacy protocols
   * we should remove this code too.
   * @param mt
   * @return
   */
  def isLegacySignalMsgNotToBeStored(mt: MsgType): Boolean = {
    val legacySignalMsgTypes =
      List(
        MSG_TYPE_CONN_REQ_RESP,
        MSG_TYPE_CONN_REQ_ACCEPTED,
        MSG_TYPE_CONN_REQ_REDIRECTED
      ).map(ConnectingProtoDef_v_0_6.msgFamily.msgType)

    if (legacySignalMsgTypes.contains(mt)) true
    else false
  }

  def packOutgoingMsg(omp: OutgoingMsgParam, toParticipantId: ParticipantId, msgPackVersion: MsgPackVersion,
                      msgSpecificRecipVerKey: Option[KeyInfo]=None): OutgoingMsgParam = {
    logger.debug(s"packing outgoing message: $omp to $msgPackVersion (msgSpecificRecipVerKeyOpt: $msgSpecificRecipVerKey")
    val toDID = ParticipantUtil.agentId(toParticipantId)
    val recipKeys = Set(msgSpecificRecipVerKey.getOrElse(KeyInfo(Right(GetVerKeyByDIDParam(toDID, getKeyFromPool = false)))))
    val packedMsg = msgExtractor.pack(msgPackVersion, omp.jsonMsg_!(), recipKeys)
    OutgoingMsgParam(packedMsg, omp.metadata.map(x => x.copy(msgPackVersionStr = msgPackVersion.toString)))
  }

  /**
   * this is mainly to track msg progress for legacy agent message handler
   * @param respMsg
   * @param sndr
   * @param reqMsgContext
   */
  def sendRespMsg(respMsg: Any, sndr: ActorRef = sender())(implicit reqMsgContext: ReqMsgContext): Unit = {
    sndr ! respMsg
    MsgProgressTracker.recordLegacyMsgSentToNextHop(NEXT_HOP_MY_EDGE_AGENT_SYNC)
  }
}
