package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.plain

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.ActorContext
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.msgoutbox.outbox.States.MsgDeliveryAttempt
import com.evernym.verity.msgoutbox.outbox.{Outbox, msg_packager}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.{DispatchParam, DispatcherType, MsgPackagingParam, MsgStoreParam, MsgTransportParam, RetryParam, WebhookParam}
import com.evernym.verity.msgoutbox.{ComMethod, ComMethodId, MsgId}
import com.typesafe.config.Config

import scala.concurrent.duration.{FiniteDuration, SECONDS}

//responsible to create sender with appropriate input for each new message dispatch
class PlainWebhookDispatcher(parentActorContext: ActorContext[Outbox.Cmd],
                             config: Config,
                             comMethodId: ComMethodId,
                             comMethod: ComMethod,
                             msgStoreParam: MsgStoreParam,
                             msgPackagingParam: MsgPackagingParam,
                             msgTransportParam: MsgTransportParam) extends DispatcherType {

  override def dispatch(msgId: MsgId,
                        deliveryAttempts: Map[String, MsgDeliveryAttempt]): Unit = {
    val currFailedAttempt = deliveryAttempts.get(comMethodId).map(_.failedCount).getOrElse(0)
    val retryParam = Option(prepareWebhookRetryParam(currFailedAttempt, config))
    val dispatchParam = DispatchParam(msgId, comMethodId, retryParam, parentActorContext.self)
    val uniqueSenderId = prepareUniqueSenderId(msgId)
    val existingSender = parentActorContext.child(uniqueSenderId)
    existingSender match {
      case None =>
        val packager = msg_packager.Packager(msgPackagingParam, msgStoreParam)
        parentActorContext.spawn(
          PlainWebhookSender(
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
      ar.toClassic ! PlainWebhookSender.Commands.Ack    //TODO: how to avoid .toClassic
    }
  }

  private def prepareUniqueSenderId(msgId: MsgId): String = comMethodId + "-" + msgId + "-" + "sender"

  private def prepareWebhookRetryParam(failedAttemptCount: Int, config: Config): RetryParam = {
    //TODO: finalize this
    val maxRetries =
      ConfigReadHelper(config)
        .getIntOption("verity.outbox.webhook.retry-policy.max-retries")
        .getOrElse(5)

    val initialInterval =
      ConfigReadHelper(config)
        .getDurationOption("verity.outbox.webhook.retry-policy.initial-interval")
        .getOrElse(FiniteDuration(5, SECONDS))

    RetryParam(
      failedAttemptCount,
      maxRetries,
      initialInterval
    )
  }
}
