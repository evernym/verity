package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.adapter._
import com.evernym.verity.msgoutbox.outbox.States.MsgDeliveryAttempt
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher._
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxConfig, msg_packager}
import com.evernym.verity.msgoutbox.{ComMethod, ComMethodId, MsgId}


//responsible to create sender with appropriate input for each new message dispatch
class OAuthWebhookDispatcher(parentActorContext: ActorContext[Outbox.Cmd],
                             oAuthAccessTokenHolder: ActorRef[OAuthAccessTokenHolder.Cmd],
                             config: OutboxConfig,
                             eventEncryptionSalt: String,
                             comMethodId: ComMethodId,
                             comMethod: ComMethod,
                             msgStoreParam: MsgStoreParam,
                             msgPackagingParam: MsgPackagingParam,
                             msgTransportParam: MsgTransportParam) extends DispatcherType {

  override def dispatch(msgId: MsgId,
                        deliveryAttempts: Map[String, MsgDeliveryAttempt]): Unit = {
    val currFailedAttempt = deliveryAttempts.get(comMethodId).map(_.failedCount).getOrElse(0)
    val retryParam = Option(Outbox.prepareRetryParam(comMethod.typ, currFailedAttempt, config))
    val dispatchParam = DispatchParam(msgId, comMethodId, retryParam, parentActorContext.self)
    val uniqueSenderId = prepareUniqueSenderId(msgId)
    val existingSender = parentActorContext.child(uniqueSenderId)
    existingSender match {
      case None =>
        val packager = msg_packager.Packager(msgPackagingParam, msgStoreParam, eventEncryptionSalt)
        parentActorContext.spawn(
          OAuthWebhookSender(
            oAuthAccessTokenHolder,
            dispatchParam,
            packager,
            WebhookParam(comMethod.value),
            msgTransportParam.httpTransporter
          ),
          uniqueSenderId
        )
      case _    => //msg sending already in progress
    }
  }

  override def ack(msgId: MsgId): Unit = {
    parentActorContext.child(prepareUniqueSenderId(msgId)).foreach { ar =>
      ar.toClassic ! OAuthWebhookSender.Commands.Ack    //TODO: how to avoid .toClassic
    }
  }

  private def prepareUniqueSenderId(msgId: MsgId): String = comMethodId + "-" + msgId + "-" + "sender"
}
