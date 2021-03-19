package com.evernym.verity.actor.agent.msghandler.outgoing

import akka.actor.ActorRef
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{MSG_DELIVERY_STATUS_FAILED, MSG_DELIVERY_STATUS_SENT}
import com.evernym.verity.actor.agent.{AgentIdentity, HasAgentActivity}
import com.evernym.verity.actor.agent.msghandler.{AgentMsgHandler, SendPushNotif, SendUnStoredMsgToMyDomain, StoreAndSendMsgToMyDomain, StoreAndSendMsgToTheirDomain}
import com.evernym.verity.actor.msg_tracer.progress_tracker.MsgEvent
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.ReqMsgContext
import com.evernym.verity.vault.KeyParam
import com.evernym.verity.push_notification.PushNotifResponse

import scala.concurrent.Future


trait AgentOutgoingMsgHandler
  extends SendOutgoingMsg
    with AgentIdentity
    with HasAgentActivity { this: AgentMsgHandler with AgentPersistentActor =>

  lazy val defaultSelfRecipKeys = Set(KeyParam.fromDID(domainId))

  def agentOutgoingCommonCmdReceiver: Receive = {
    case spn: SendPushNotif                 => handleSendPushNotif(spn)
    case sm: SendUnStoredMsgToMyDomain      => sendUnStoredMsgToMyDomain(sm.omp)
    case sm: StoreAndSendMsgToMyDomain      =>
      storeOutgoingMsg(sm.om, sm.msgId, sm.msgName, sm.senderDID, sm.threadOpt)
      sendStoredMsgToMyDomain(sm.msgId)
    case sm: StoreAndSendMsgToTheirDomain   =>
      storeOutgoingMsg(sm.om, sm.msgId, sm.msgName, sm.senderDID, sm.threadOpt)
      sendStoredMsgToTheirDomain(sm.om, sm.msgId, sm.msgName, sm.threadOpt)

    //this actor -> this actor
    case ssm: SendStoredMsgToMyDomain       => handleSendStoredMsgToMyDomain(ssm.msgId)
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

  def handleSendStoredMsgToMyDomain(uid: MsgId): Unit = {
    sendStoredMsgToSelf(uid)
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
      sndr ! msg
      recordOutMsgEvent(reqMsgContext.id,
        MsgEvent(s"${reqMsgContext.id}", s"$respMsgType (just a hint)", s"SENT: synchronous response to caller"))
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
