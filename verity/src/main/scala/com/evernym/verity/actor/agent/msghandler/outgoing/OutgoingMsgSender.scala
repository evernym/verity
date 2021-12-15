package com.evernym.verity.actor.agent.msghandler.outgoing

import akka.actor.{Actor, Props}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.{MsgSendingFailed, MsgSentSuccessfully}
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.actor.agent.msghandler.{SendMsgToMyDomain, SendMsgToTheirDomain}
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.did.DidStr
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.MsgName
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass

import scala.concurrent.duration._

/**
 * Idea behind this actor is to orchestrate (sending and retrying) message delivery
 */
class OutgoingMsgSender(maxRetryAttempt: Int, initialDelayInSeconds: Int)
  extends CoreActorExtended {

  override def receiveCmd: Receive = {
    case smd: SendMsgToMyDomain     =>
      val cmd = ProcessSendMsgToMyDomain(smd)
      sendCmdToParent(cmd)
      setNewReceiveBehaviour(retryIfFailed(cmd))

    case std: SendMsgToTheirDomain  =>
      val cmd = ProcessSendMsgToTheirDomain(std)
      sendCmdToParent(cmd)
      setNewReceiveBehaviour(retryIfFailed(cmd))
  }

  def retryIfFailed(cmd: ProcessSendMsgBase): Receive = {
    case _: MsgSentSuccessfully  => stopActor()
    case _: MsgSendingFailed     => handleMsgSendingFailed(cmd)
    case Retry                   => sendCmdToParent(cmd)
  }

  def handleMsgSendingFailed(cmd: ProcessSendMsgBase): Unit = {
    failedAttempts += 1

    if (failedAttempts <= maxRetryAttempt) {
      logger.info(s"[${cmd.msgId}:${cmd.msgName}] message sending failed: [$failedAttempts/$maxRetryAttempt]")
      val timeout = Duration(failedAttempts * initialDelayInSeconds, SECONDS)
      context.setReceiveTimeout(timeout.plus(timeout.plus(15.seconds)))
      timers.startSingleTimer("retry", Retry, timeout)
    } else {
      logger.info(s"[${cmd.msgId}:${cmd.msgName}] message delivery failed: [$failedAttempts/$maxRetryAttempt]")
      stopActor()
    }
  }

  def sendCmdToParent(cmd: Any): Unit = {
    context.parent ! cmd
    context.setReceiveTimeout(30.seconds)
  }

  var failedAttempts = 0

  private val logger = getLoggerByClass(getClass)
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

trait ProcessSendMsgBase extends ActorMessage {
  def msgId: MsgId
  def msgName: MsgName
}

case class ProcessSendMsgToMyDomain(om: OutgoingMsgParam,
                                    msgId: MsgId,
                                    msgName: MsgName,
                                    senderDID: DidStr,
                                    threadOpt: Option[Thread]) extends ProcessSendMsgBase

object ProcessSendMsgToTheirDomain {
  def apply(smd: SendMsgToTheirDomain): ProcessSendMsgToTheirDomain =
    ProcessSendMsgToTheirDomain(smd.om, smd.msgId, smd.msgName, smd.senderDID, smd.threadOpt)
}
case class ProcessSendMsgToTheirDomain(om: OutgoingMsgParam,
                                       msgId: MsgId,
                                       msgName: MsgName,
                                       senderDID: DidStr,
                                       threadOpt: Option[Thread]) extends ProcessSendMsgBase


case object Retry extends ActorMessage