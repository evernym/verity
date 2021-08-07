package com.evernym.verity.actor.agent.msghandler.outgoing

import akka.actor.{Actor, Props}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.actor.agent.msghandler.{SendMsgToMyDomain, SendMsgToTheirDomain}
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.engine.{MsgId, MsgName}
import com.evernym.verity.protocol.protocols.{MsgSendingFailed, MsgSentSuccessfully}

import scala.concurrent.duration._

/**
 * Idea behind this actor is to orchestrate (sending and retrying) message delivery
 */
class OutgoingMsgSender(maxRetryAttempt: Int, initialDelayInSeconds: Int)
  extends CoreActorExtended {

  override def receiveCmd: Receive = {
    case smd: SendMsgToMyDomain     =>
      sendCmdToParent(ProcessSendMsgToMyDomain(smd))
      stopActor()

    case std: SendMsgToTheirDomain  =>
      val cmd = ProcessSendMsgToTheirDomain(std)
      sendCmdToParent(cmd)
      setNewReceiveBehaviour(theirDomainNotifier(cmd))
  }

  def theirDomainNotifier(cmd: ProcessSendMsgToTheirDomain): Receive = {
    case _: MsgSendingFailed     => handleMsgSendingFailed()
    case _: MsgSentSuccessfully  => stopActor()
    case Retry                   => sendCmdToParent(cmd)
  }

  def handleMsgSendingFailed(): Unit = {
    failedAttempts += 1

    if (failedAttempts <= maxRetryAttempt) {
      val timeout = Duration(failedAttempts * initialDelayInSeconds, SECONDS)
      context.setReceiveTimeout(timeout.plus(timeout.plus(15.seconds)))
      timers.startSingleTimer("retry", Retry, timeout)
    } else {
      stopActor()
    }
  }

  def sendCmdToParent(cmd: Any): Unit = {
    context.parent ! cmd
    context.setReceiveTimeout(30.seconds)
  }

  var failedAttempts = 0
}

trait HasOutgoingMsgSender { this: Actor =>

  val maxRetryAttempt: Int = 5
  val initialDelayInSeconds: Int = 15

  def forwardToOutgoingMsgSenderIfExists(msgId: MsgId, cmd: Any): Unit = {
    forwardToOutgoingMsgSender(msgId, cmd, onlyIfActorExists = true)
  }

  def forwardToOutgoingMsgSender(msgId: MsgId, cmd: Any, onlyIfActorExists: Boolean = false): Unit = {
    val childActor =
      context.child(msgId) orElse
        (if (onlyIfActorExists) None else Option(context.actorOf(
          OutgoingMsgSender.props(maxRetryAttempt, initialDelayInSeconds), msgId)))
    childActor.foreach(_.forward(cmd))
  }
}

object OutgoingMsgSender {
  def props(maxRetryAttempt: Int, initialDelayInSeconds: Int): Props =
    Props(new OutgoingMsgSender(maxRetryAttempt, initialDelayInSeconds))
}

object ProcessSendMsgToMyDomain {
  def apply(smd: SendMsgToMyDomain): ProcessSendMsgToMyDomain =
    ProcessSendMsgToMyDomain(smd.om, smd.msgId, smd.msgName, smd.senderDID, smd.threadOpt)
}

case class ProcessSendMsgToMyDomain(om: OutgoingMsgParam,
                                    msgId: MsgId,
                                    msgName: MsgName,
                                    senderDID: DidStr,
                                    threadOpt: Option[Thread]) extends ActorMessage

object ProcessSendMsgToTheirDomain {
  def apply(smd: SendMsgToTheirDomain): ProcessSendMsgToTheirDomain =
    ProcessSendMsgToTheirDomain(smd.om, smd.msgId, smd.msgName, smd.senderDID, smd.threadOpt)
}
case class ProcessSendMsgToTheirDomain(om: OutgoingMsgParam,
                                       msgId: MsgId,
                                       msgName: MsgName,
                                       senderDID: DidStr,
                                       threadOpt: Option[Thread]) extends ActorMessage


case object Retry extends ActorMessage