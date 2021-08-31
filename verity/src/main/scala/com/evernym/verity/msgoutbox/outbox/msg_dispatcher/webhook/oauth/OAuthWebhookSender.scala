package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.RawHeader
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.util2.Status
import com.evernym.verity.msgoutbox.outbox.Outbox
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.ResponseHandler
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder.Commands.GetToken
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder.Replies.{AuthToken, GetTokenFailed}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthWebhookSender.Commands.{HttpTransporterReplyAdapter, OAuthAccessTokenHolderReplyAdapter, PackedMsg, Send}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.{DispatchParam, RetryParam, WebhookParam}
import com.evernym.verity.msgoutbox.outbox.msg_packager.Packager
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter.Replies.SendResponse
import com.evernym.verity.util2.Status.StatusDetail

import scala.concurrent.duration.DurationInt
import scala.collection.immutable


//responsible to
// send given message to webhook with oauth,
// retry based on given parameter,
// report back each delivery attempt status to outbox and
// stop when message is delivered or can't be delivered after attempting given retries
object OAuthWebhookSender {

  trait Cmd extends ActorMessage
  object Commands {
    case object Send extends Cmd
    trait PackedMsg extends Cmd
    case class UnPackedMsg(msg: String) extends PackedMsg
    case class DIDCommV1PackedMsg(msg: Array[Byte]) extends PackedMsg

    case object Ack extends Cmd

    case class HttpTransporterReplyAdapter(reply: HttpTransporter.Reply) extends Cmd
    case class OAuthAccessTokenHolderReplyAdapter(reply: OAuthAccessTokenHolder.Reply) extends Cmd

    case object TimedOut extends Cmd
  }

  def apply[P](oAuthAccessTokenHolder: ActorRef[OAuthAccessTokenHolder.Cmd],
               dispatchParam: DispatchParam,
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
        handlePackedMsg(oAuthAccessTokenHolder, dispatchParam, webhookParam, httpTransporter)(actorContext, timer)
      }
    }
  }

  private def handlePackedMsg(oAuthAccessTokenHolder: ActorRef[OAuthAccessTokenHolder.Cmd],
                              dispatchParam: DispatchParam,
                              webhookParam: WebhookParam,
                              httpTransporter: Behavior[HttpTransporter.Cmd])
                             (implicit actorContext: ActorContext[Cmd],
                              timer: TimerScheduler[Cmd]): Behavior[Cmd] = {
    Behaviors.receiveMessage {
      case pm: PackedMsg =>
        val httpTransporterRef = actorContext.spawnAnonymous(httpTransporter)
        val httpTransporterReplyAdapter = actorContext.messageAdapter(reply => HttpTransporterReplyAdapter(reply))
        val oAuthAccessTokenHolderReplyAdapter = actorContext.messageAdapter(reply => OAuthAccessTokenHolderReplyAdapter(reply))
        val sendMsgParam = SendMsgParam(
          dispatchParam,
          webhookParam,
          oAuthAccessTokenHolder,
          oAuthAccessTokenHolderReplyAdapter,
          httpTransporterRef,
          httpTransporterReplyAdapter
        )
        oAuthAccessTokenHolder ! GetToken(refreshed = false, oAuthAccessTokenHolderReplyAdapter)
        waitingForOAuthAccessToken(pm, sendMsgParam)
    }
  }

  private def waitingForOAuthAccessToken(packedMsg: Any,
                                         sendMsgParam: SendMsgParam)
                                        (implicit actorContext: ActorContext[Cmd],
                                         timer: TimerScheduler[Cmd]): Behavior[Cmd] = {
    Behaviors.receiveMessage {
      case Commands.OAuthAccessTokenHolderReplyAdapter(reply: AuthToken) =>
        actorContext.self ! Send
        sendingMsg(
          immutable.Seq(RawHeader("Authorization", s"Bearer ${reply.value}")),
          packedMsg,
          sendMsgParam
        )
      case Commands.OAuthAccessTokenHolderReplyAdapter(reply: GetTokenFailed) =>
        sendMsgParam.dispatchParam.replyTo !
          Outbox.Commands.RecordFailedAttempt(
            sendMsgParam.dispatchParam.msgId,
            sendMsgParam.dispatchParam.comMethodId,
            sendAck = true,
            isItANotification = false,
            sendMsgParam.dispatchParam.retryParam.exists(_.isRetryAttemptsLeft),
            StatusDetail(Status.UNHANDLED.statusCode, reply.errorMsg)
          )
        if (sendMsgParam.dispatchParam.retryParam.exists(_.isRetryAttemptsLeft)) {
          sendMsgParam.oAuthAccessTokenHolder ! GetToken(refreshed = false, sendMsgParam.oAuthAccessTokenHolderReplyAdapter)
          waitingForOAuthAccessToken(packedMsg, sendMsgParam.withFailureAttemptIncremented)
        } else {
          msgSendingFinished
        }
    }
  }

  private def sendingMsg(headers: immutable.Seq[HttpHeader],
                         packedMsg: Any,
                         sendMsgParam: SendMsgParam)
                        (implicit actorContext: ActorContext[Cmd],
                         timer: TimerScheduler[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case Commands.Send =>

      packedMsg match {
        case Commands.UnPackedMsg(msg) =>
          sendMsgParam.httpTransporterRef ! HttpTransporter.Commands.SendJson(
            msg, sendMsgParam.webhookParam.url, headers, sendMsgParam.httpTransporterReplyAdapter)
          Behaviors.same

        case Commands.DIDCommV1PackedMsg(msg) =>
          sendMsgParam.httpTransporterRef ! HttpTransporter.Commands.SendBinary(
            msg, sendMsgParam.webhookParam.url, headers, sendMsgParam.httpTransporterReplyAdapter)
          Behaviors.same
      }

    case HttpTransporterReplyAdapter(reply: SendResponse) =>
      ResponseHandler.handleResp[Cmd](sendMsgParam.dispatchParam, reply.resp, Commands.Send)(timer) match {
        case Some(retryParam) =>
          reply.resp match {
            case Left(he) if he.statusCode == Status.UNAUTHORIZED.statusCode =>
              sendMsgParam.oAuthAccessTokenHolder ! GetToken(refreshed = true, sendMsgParam.oAuthAccessTokenHolderReplyAdapter)
              waitingForOAuthAccessToken(packedMsg, sendMsgParam)
            case _ =>
              sendingMsg(headers, packedMsg, sendMsgParam.withRetryParam(retryParam))
          }
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
          headers,
          packedMsg,
          sendMsgParam.withFailureAttemptIncremented
        )
      }
  }

  private def msgSendingFinished: Behavior[Cmd] = Behaviors.receiveMessage {
    case Commands.Ack       => Behaviors.stopped
    case Commands.TimedOut  => Behaviors.stopped
  }
}

case class SendMsgParam(dispatchParam: DispatchParam,
                        webhookParam: WebhookParam,
                        oAuthAccessTokenHolder: ActorRef[OAuthAccessTokenHolder.Cmd],
                        oAuthAccessTokenHolderReplyAdapter: ActorRef[OAuthAccessTokenHolder.Reply],
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

