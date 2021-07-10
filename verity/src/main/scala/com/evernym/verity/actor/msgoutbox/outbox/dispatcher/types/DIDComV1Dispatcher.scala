package com.evernym.verity.actor.msgoutbox.outbox.dispatcher.types

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.msgoutbox.Packaging
import com.evernym.verity.actor.msgoutbox.adapters.HttpTransporter.Commands.SendBinary
import com.evernym.verity.actor.msgoutbox.adapters.HttpTransporter.Replies.SendResponse
import com.evernym.verity.actor.msgoutbox.adapters.{HttpTransporter, WalletOpExecutor}
import com.evernym.verity.actor.msgoutbox.outbox.Outbox.Commands.RecordFailedAttempt
import com.evernym.verity.actor.msgoutbox.outbox._
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.types.DIDComV1Dispatcher.Commands.{DeliverMsg, HttpTransportReplyAdapter, Stop, WalletReplyAdapter}
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.urlshortener.UrlInfo
import com.evernym.verity.util2.Status

import scala.concurrent.duration._

//ephemeral child actor (of MessageDispatcher actor) responsible to deliver DID Com V1 packaged message.
//after successful or exhausted retries, it will stop itself (mostly it is short lived if everything goes fine)
object DIDComV1Dispatcher {

  trait Cmd
  object Commands {
    case object DeliverMsg extends Cmd
    case class WalletReplyAdapter(reply: WalletOpExecutor.Reply) extends Cmd
    case class HttpTransportReplyAdapter(reply: HttpTransporter.Reply) extends Cmd

    case object Stop extends Cmd
  }

  def apply(param: DIDComV1DispatchParam): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val walletOpExecutor = actorContext.spawnAnonymous(param.packagingParam.walletOpExecutor)
      val httpTransporter = actorContext.spawnAnonymous(param.transportParam.behavior)
      val walletOpExecutorReplyAdapter = actorContext.messageAdapter(reply => WalletReplyAdapter(reply))
      val httpTransporterReplyAdapter = actorContext.messageAdapter(reply => HttpTransportReplyAdapter(reply))
      actorContext.setReceiveTimeout(15.seconds, Commands.Stop)   //TODO: finalize this
      Behaviors.withTimers { timer =>
        initialized(
          param,
          walletOpExecutor,
          walletOpExecutorReplyAdapter,
          httpTransporter,
          httpTransporterReplyAdapter)(timer, actorContext)
      }
    }
  }

  private def initialized(param: DIDComV1DispatchParam,
                          walletOpExecutor: ActorRef[WalletOpExecutor.Cmd],
                          walletOpExecutorReplyAdapter: ActorRef[WalletOpExecutor.Reply],
                          httpTransporter: ActorRef[HttpTransporter.Cmd],
                          httpTransporterReplyAdapter: ActorRef[HttpTransporter.Reply])
                         (implicit timer: TimerScheduler[Cmd],
                          actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {

    case Commands.DeliverMsg =>
      walletOpExecutor !
          WalletOpExecutor.Commands.PackMsg(
            param.payloadParam.payload,
            param.packagingParam.walletId,
            param.packagingParam.recipKeys,
            param.packagingParam.senderVerKey,
            walletOpExecutorReplyAdapter
          )
        Behaviors.same

    case Commands.WalletReplyAdapter(reply: WalletOpExecutor.Replies.PackagedPayload) =>
      if (param.transportParam.typ == COM_METHOD_TYPE_HTTP_ENDPOINT) {
        actorContext.self ! DeliverMsg
        sendingViaHttpTransport(
          param.withNewPayload(reply.payload),
          httpTransporter,
          httpTransporterReplyAdapter
        )
      } else {
        Behaviors.unhandled
      }
  }

  private def sendingViaHttpTransport(param: DIDComV1DispatchParam,
                                      httpTransporter: ActorRef[HttpTransporter.Cmd],
                                      httpTransportReplyMapper: ActorRef[HttpTransporter.Reply])
                                    (implicit timer: TimerScheduler[Cmd],
                                     actorContext: ActorContext[Cmd]): Behavior[Cmd] =
    Behaviors.receiveMessage[Cmd] {

      case Commands.DeliverMsg =>   //needed to handle retries
        httpTransporter ! SendBinary(
          param.payloadParam.payload,
          UrlInfo(param.transportParam.url),
          httpTransportReplyMapper)
        Behaviors.same

      case HttpTransportReplyAdapter(reply: SendResponse) =>
        ResponseHandler.handleResp[Cmd](param, reply.resp, DeliverMsg) match {
          case Some(retryParam) =>
            sendingViaHttpTransport(
              param.copy(retryParam = Option(retryParam)),
              httpTransporter,
              httpTransportReplyMapper
            )
          case None             =>
            Behaviors.stopped
        }

      case Stop =>
        //this means, it never got a response from webhook adapter and timed out
        actorContext.self ! DeliverMsg
        if (param.retryParam.forall(_.isRetryAttemptExhausted)) {
          param.replyTo !
            RecordFailedAttempt(
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
            httpTransportReplyMapper
          )
        }
    }
}

case class DIDComV1DispatchParam(msgId: MsgId,
                                 comMethodId: ComMethodId,
                                 retryParam: Option[RetryParam],
                                 replyTo: ActorRef[Outbox.Cmd],
                                 payloadParam: BinaryPayloadParam,
                                 packagingParam: IndyPackagingParam,
                                 transportParam: HttpTransportParam)
  extends BaseDispatchParam {
    def withNewPayload(payload: Array[Byte]): DIDComV1DispatchParam =
      copy(payloadParam = payloadParam.copy(payload = payload))
}

case class IndyPackagingParam(packaging: Packaging,
                              senderVerKey: VerKey,
                              walletId: WalletId,
                              walletOpExecutor: Behavior[WalletOpExecutor.Cmd])
  extends PackagingParam {
  def recipKeys: Set[VerKey] = packaging.recipientKeys.toSet
}