package com.evernym.verity.actor.agent.msghandler.outgoing

import akka.actor.ActorRef
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{MSG_DELIVERY_STATUS_FAILED, MSG_DELIVERY_STATUS_SENT}
import com.evernym.verity.actor.agent.{AgentIdentity, HasAgentActivity}
import com.evernym.verity.actor.agent.msghandler.{AgentMsgHandler, SendPushNotif, SendUnStoredMsgToMyDomain, StoreAndSendMsgToMyDomain, StoreAndSendMsgToTheirDomain}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.msg_tracer.MsgTraceProvider._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.ReqMsgContext
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyParam}
import com.evernym.verity.push_notification.PushNotifResponse

import scala.concurrent.Future


trait AgentOutgoingMsgHandler
  extends SendOutgoingMsg
    with AgentIdentity
    with HasAgentActivity { this: AgentMsgHandler with AgentPersistentActor =>

  lazy val defaultSelfRecipKeys = Set(KeyParam(Right(GetVerKeyByDIDParam(domainId, getKeyFromPool = false))))

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
    val sndr = sender()
    sendStoredMsgToSelf(uid) map { _ =>
      sndr ! Done
    }
  }


  /**
   * this is mainly to track msg progress for legacy agent message handler
   * @param respMsg
   * @param sndr
   * @param reqMsgContext
   */
  def sendRespMsg(respMsg: Any, sndr: ActorRef = sender())(implicit reqMsgContext: ReqMsgContext): Unit = {
    def sendAndRecordMetrics(msg: Any, sndr: ActorRef): Unit = {
      sndr ! msg
      MsgProgressTracker.recordLegacyMsgSentToNextHop(NEXT_HOP_MY_EDGE_AGENT_SYNC)
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
