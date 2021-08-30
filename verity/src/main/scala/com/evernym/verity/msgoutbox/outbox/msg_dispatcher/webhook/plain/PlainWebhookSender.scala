package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.plain

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.util2.Status
import com.evernym.verity.msgoutbox.outbox.Outbox
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.ResponseHandler
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.plain.PlainWebhookSender.Commands.{HttpTransporterReplyAdapter, PackedMsg}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.{DispatchParam, RetryParam, WebhookParam}
import com.evernym.verity.msgoutbox.outbox.msg_packager.Packager
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter.Replies.SendResponse
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration.DurationInt
import scala.collection.immutable


//responsible to
// send given message to webhook,
// retry based on given parameter,
// report back each delivery attempt status to outbox and
// stop when message is delivered or can't be delivered after attempting given retries
object PlainWebhookSender {

  sealed trait Cmd extends ActorMessage
  object Commands {
    case object Send extends Cmd
    trait PackedMsg extends Cmd
    case class UnPackedMsg(msg: String) extends PackedMsg
    case class DIDCommV1PackedMsg(msg: Array[Byte]) extends PackedMsg

    case object Ack extends Cmd

    case class HttpTransporterReplyAdapter(reply: HttpTransporter.Reply) extends Cmd

    case object TimedOut extends Cmd
  }

  private val logger: Logger = getLoggerByClass(getClass)

  def apply[P](dispatchParam: DispatchParam,
               packager: Behavior[Packager.Cmd],
               webhookParam: WebhookParam,
               httpTransporter: Behavior[HttpTransporter.Cmd]): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      Behaviors.withTimers { timer =>
        actorContext.setReceiveTimeout(20.seconds, Commands.TimedOut)   //TODO: finalize this
        val packagerRef = actorContext.spawnAnonymous(packager)
        val packagerReplyAdapter = actorContext.messageAdapter { reply: Packager.Reply =>
          reply match {
            case Packager.Replies.UnPackedMsg(msg)        => Commands.UnPackedMsg(msg)
            case Packager.Replies.DIDCommV1PackedMsg(msg) => Commands.DIDCommV1PackedMsg(msg)
          }
        }
        packagerRef ! Packager.Commands.PackMsg(dispatchParam.msgId, packagerReplyAdapter)
        handlePackedMsg(dispatchParam, webhookParam, httpTransporter)(actorContext, timer)
      }
    }
  }

  private def handlePackedMsg(dispatchParam: DispatchParam,
                              webhookParam: WebhookParam,
                              httpTransporter: Behavior[HttpTransporter.Cmd])
                             (implicit actorContext: ActorContext[Cmd],
                              timer: TimerScheduler[Cmd]): Behavior[Cmd] = {
    Behaviors.receiveMessage {
      case pm: PackedMsg =>
        val httpTransporterRef = actorContext.spawnAnonymous(httpTransporter)
        val httpTransporterReplyAdapter = actorContext.messageAdapter(reply => HttpTransporterReplyAdapter(reply))
        val sendMsgParam = SendMsgParam(
          dispatchParam,
          webhookParam,
          httpTransporterRef,
          httpTransporterReplyAdapter
        )
        actorContext.self ! Commands.Send
        sendingMsg(pm, sendMsgParam)

      case cmd =>
        logger.error(s"Received unexpected message ${cmd}")
        Behaviors.same
    }
  }

  private def sendingMsg(packedMsg: Any,
                         sendMsgParam: SendMsgParam)
                        (implicit actorContext: ActorContext[Cmd],
                         timer: TimerScheduler[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case Commands.Send =>

      packedMsg match {
        case Commands.UnPackedMsg(msg) =>
          sendMsgParam.httpTransporterRef ! HttpTransporter.Commands.SendJson(
            msg, sendMsgParam.webhookParam.url, immutable.Seq.empty, sendMsgParam.httpTransporterReplyAdapter)
          Behaviors.same

        case Commands.DIDCommV1PackedMsg(msg) =>
          sendMsgParam.httpTransporterRef ! HttpTransporter.Commands.SendBinary(
            msg, sendMsgParam.webhookParam.url, immutable.Seq.empty, sendMsgParam.httpTransporterReplyAdapter)
          Behaviors.same
      }

    case HttpTransporterReplyAdapter(reply: SendResponse) =>
      ResponseHandler.handleResp[Cmd](sendMsgParam.dispatchParam, reply.resp, Commands.Send)(timer) match {
        case Some(retryParam) => sendingMsg(packedMsg, sendMsgParam.withRetryParam(retryParam))
        case None             => msgSendingFinished
      }

    case Commands.TimedOut =>
      if (sendMsgParam.dispatchParam.retryParam.forall(_.isRetryAttemptExhausted)) {
        sendMsgParam.dispatchParam.replyTo ! Outbox.Commands.RecordFailedAttempt(
          sendMsgParam.dispatchParam.msgId,
          sendMsgParam.dispatchParam.comMethodId,
          sendAck = true,
          isItANotification = false,
          isAnyRetryAttemptsLeft = false,
          Status.UNHANDLED
        )
        msgSendingFinished
      } else {
        actorContext.self ! Commands.Send
        sendingMsg(
          packedMsg,
          sendMsgParam.withFailureAttemptIncremented
        )
      }
    case cmd =>
      logger.error(s"Received unexpected message ${cmd}")
      Behaviors.same
  }

  private def msgSendingFinished: Behavior[Cmd] = Behaviors.receiveMessage {
    case Commands.Ack       => Behaviors.stopped
    case Commands.TimedOut  => Behaviors.stopped
    case cmd =>
      logger.error(s"Received unexpected message ${cmd}")
      Behaviors.same
  }
}

case class SendMsgParam(dispatchParam: DispatchParam,
                        webhookParam: WebhookParam,
                        httpTransporterRef: ActorRef[HttpTransporter.Cmd],
                        httpTransporterReplyAdapter: ActorRef[HttpTransporter.Reply]) {
  def withRetryParam(retryParam: RetryParam): SendMsgParam =
    copy(dispatchParam = dispatchParam.copy(retryParam = Option(retryParam)))

  def withFailureAttemptIncremented: SendMsgParam =
    dispatchParam.retryParam match {
      case Some(rp) => copy(dispatchParam = dispatchParam.copy(retryParam = Option(rp.withFailedAttemptIncremented)))
      case None     => this
    }
}

