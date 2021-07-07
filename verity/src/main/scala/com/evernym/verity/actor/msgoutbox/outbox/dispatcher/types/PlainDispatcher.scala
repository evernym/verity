package com.evernym.verity.actor.msgoutbox.outbox.dispatcher.types

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.msgoutbox.adapters.HttpTransporter.Commands.SendJson
import com.evernym.verity.actor.msgoutbox.adapters.HttpTransporter.Replies.SendResponse
import com.evernym.verity.actor.msgoutbox.adapters.{HttpTransporter, WalletOpExecutor}
import com.evernym.verity.actor.msgoutbox.outbox.Outbox.Commands.RecordFailedAttempt
import com.evernym.verity.actor.msgoutbox.outbox._
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.types.PlainDispatcher.Commands.{DeliverMsg, HttpTransporterReplyAdapter, Stop}
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.urlshortener.UrlInfo
import com.evernym.verity.util2.Status

import scala.concurrent.duration._

//ephemeral child actor (of MessageDispatcher actor) responsible to deliver a plain (json) message
//after successful or exhausted retries, it will stop itself (mostly it is short lived if everything goes fine)
object PlainDispatcher {

  trait Cmd
  object Commands {
    case object DeliverMsg extends Cmd
    case class WalletOpExecutorReplyAdapter(reply: WalletOpExecutor.Reply) extends Cmd
    case class HttpTransporterReplyAdapter(reply: HttpTransporter.Reply) extends Cmd

    case object Stop extends Cmd
  }

  def apply(param: PlainDispatchParam): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val httpTransporter = actorContext.spawnAnonymous(param.transportParam.behavior)
      val httpTransporterReplyAdapter = actorContext.messageAdapter(reply => HttpTransporterReplyAdapter(reply))
      actorContext.setReceiveTimeout(15.seconds, Commands.Stop)   //TODO: finalize this
      Behaviors.withTimers { timer =>
        initialized(param, httpTransporter, httpTransporterReplyAdapter)(timer, actorContext)
      }
    }
  }

  private def initialized(param: PlainDispatchParam,
                          httpTransporter: ActorRef[HttpTransporter.Cmd],
                          httpTransportReplyAdapter: ActorRef[HttpTransporter.Reply])
                         (implicit timer: TimerScheduler[Cmd],
                          actorContext: ActorContext[Cmd]): Behavior[Cmd] =
    Behaviors.receiveMessage[Cmd] {

      case DeliverMsg =>
        if (param.transportParam.typ == COM_METHOD_TYPE_HTTP_ENDPOINT) {
          actorContext.self ! DeliverMsg
          sendingViaHttpTransport(param, httpTransporter, httpTransportReplyAdapter)
        } else {
          Behaviors.unhandled
        }

    }

  private def sendingViaHttpTransport(param: PlainDispatchParam,
                                      httpTransporter: ActorRef[HttpTransporter.Cmd],
                                      httpTransporterReplyAdapter: ActorRef[HttpTransporter.Reply])
                                     (implicit timer: TimerScheduler[Cmd],
                                      actorContext: ActorContext[Cmd]): Behavior[Cmd] = {
    Behaviors.receiveMessage[Cmd] {

      case DeliverMsg =>    //needed to handle retries
        httpTransporter ! SendJson(param.payloadParam.payload, UrlInfo(param.transportParam.url), httpTransporterReplyAdapter)
        Behaviors.same

      case HttpTransporterReplyAdapter(reply: SendResponse) =>
        ResponseHandler.handleResp[Cmd](param, reply.resp, DeliverMsg) match {
          case Some(retryParam) =>
            sendingViaHttpTransport(
              param.copy(retryParam = Option(retryParam)),
              httpTransporter,
              httpTransporterReplyAdapter)
          case None =>
            Behaviors.stopped
        }

      case Stop =>
        //this means, it never got a response from webhook adapter and timed out
        actorContext.self ! DeliverMsg
        if (param.retryParam.forall(_.isRetryAttemptExhausted)) {
          param.replyTo ! RecordFailedAttempt(
            param.msgId,
            param.comMethodId,
            isItANotification = false,
            isAnyRetryAttemptsLeft = false,
            Status.UNHANDLED
          )
          Behaviors.stopped
        } else {
          sendingViaHttpTransport(
            param.copy(retryParam = param.retryParam.map(_.withFailedAttemptIncremented)),
            httpTransporter,
            httpTransporterReplyAdapter
          )
        }
    }
  }
}

case class PlainDispatchParam(msgId: MsgId,
                              comMethodId: ComMethodId,
                              retryParam: Option[RetryParam],
                              replyTo: ActorRef[Outbox.Cmd],
                              payloadParam: PlainPayloadParam,
                              transportParam: HttpTransportParam)
  extends BaseDispatchParam {
  def packagingParam: PackagingParam = NoPackaging()
}

case class NoPackaging() extends PackagingParam