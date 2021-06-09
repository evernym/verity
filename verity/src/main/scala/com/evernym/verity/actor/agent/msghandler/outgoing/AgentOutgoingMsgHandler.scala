package com.evernym.verity.actor.agent.msghandler.outgoing

import akka.actor.ActorRef
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{MSG_DELIVERY_STATUS_FAILED, MSG_DELIVERY_STATUS_SENT}
import com.evernym.verity.actor.agent.{AgentIdentity, HasAgentActivity}
import com.evernym.verity.actor.agent.msghandler.{AgentMsgHandler, SendPushNotif, SendUnStoredMsgToMyDomain, SendMsgToMyDomain, SendMsgToTheirDomain}
import com.evernym.verity.actor.msg_tracer.progress_tracker.MsgEvent
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.util.ReqMsgContext
import com.evernym.verity.vault.KeyParam
import com.evernym.verity.push_notification.PushNotifResponse

import scala.concurrent.Future


trait AgentOutgoingMsgHandler
  extends SendOutgoingMsg
    with AgentIdentity
    with HasOutgoingMsgSender
    with HasAgentActivity { this: AgentMsgHandler with AgentPersistentActor =>

  lazy val defaultSelfRecipKeys = Set(KeyParam.fromDID(domainId))

  def agentOutgoingCommonCmdReceiver: Receive = {
    case spn: SendPushNotif                 => handleSendPushNotif(spn)
    case sm: SendUnStoredMsgToMyDomain      => sendMsgToMyDomain(sm.omp, sm.msgId, sm.msgName)

    case sm: SendMsgToMyDomain      =>
      //TODO: this conditional logic (VAS vs non VAS) is temporary only
      // until we design outbox with proper data retention capability
      if (isVAS) {
        forwardToOutgoingMsgSender(sm.msgId, sm)
      } else {
        storeOutgoingMsg(sm.om, sm.msgId, sm.msgName, sm.senderDID, sm.threadOpt)
        self ! ProcessSendMsgToMyDomain(sm)
      }
    case sm: SendMsgToTheirDomain   =>
      //TODO: this conditional logic (VAS vs non VAS) is temporary only
      // until we design outbox with proper data retention capability
      if (isVAS) {
        forwardToOutgoingMsgSender(sm.msgId, sm)
      } else {
        storeOutgoingMsg(sm.om, sm.msgId, sm.msgName, sm.senderDID, sm.threadOpt)
        self ! ProcessSendMsgToTheirDomain(sm)
      }

    //OutgoingMsgSender -> this actor (will be only used on VAS due to different data retention expectation)
    case psm: ProcessSendMsgToMyDomain      => sendMsgToMyDomain(psm.om, psm.msgId, psm.msgName)
    case pst: ProcessSendMsgToTheirDomain   => sendMsgToTheirDomain(pst.om, pst.msgId, pst.msgName, pst.threadOpt)
  }

  def handleSendPushNotif(spn: SendPushNotif): Unit = {
    sendPushNotif(spn.pcms, spn.pnData, spn.sponsorId)
    .map {
      case pnr: PushNotifResponse if MSG_DELIVERY_STATUS_SENT.hasStatusCode(pnr.statusCode) =>
        logger.trace(s"push notification sent successfully: $pnr")
      case pnr: PushNotifResponse if MSG_DELIVERY_STATUS_FAILED.hasStatusCode(pnr.statusCode) =>
        //TODO: How do we communicate a failed response? Change Actor state?
        logger.error(s"push notification failed (participantId: ${spn.pcms.map(_.value).mkString(",")}): $pnr")
      case x =>
        //TODO: How do we communicate a failed response? Change Actor state?
        logger.error(s"push notification failed (participantId: ${spn.pcms.map(_.value).mkString(",")}): $x")
    }
  }


  /**
   * this is mainly to track msg progress for legacy agent message handler
   * @param respMsgType
   * @param respMsg
   * @param sndr
   * @param reqMsgContext
   */
  def sendRespMsg(respMsgType: String,
                  respMsg: Any,
                  sndr: ActorRef = sender())(implicit reqMsgContext: ReqMsgContext): Unit = {
    def sendAndRecordMetrics(msg: Any, sndr: ActorRef): Unit = {
      //the 'respMsgType' is 'just a high level hint' about which the response message type
      // exact message type would be different word with some version info
      recordOutMsgEvent(reqMsgContext.id,
        MsgEvent(reqMsgContext.id, s"$respMsgType (just a hint)"))
      sndr ! msg
      recordOutMsgDeliveryEvent(reqMsgContext.id,
        MsgEvent.withIdAndDetail(reqMsgContext.id, s"SENT [synchronous response to caller]"))
    }
    respMsg match {
      case fut: Future[Any] => fut.map { msg =>
        sendAndRecordMetrics(msg, sndr)
      }
      case msg: Any         =>
        sendAndRecordMetrics(msg, sndr)
    }
  }
}
