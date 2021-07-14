package com.evernym.verity.msgoutbox.outbox

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.msgoutbox.{ComMethod, ComMethodId, MsgId, VerKey, WalletId}
import com.evernym.verity.msgoutbox.outbox.States.MsgDeliveryAttempt
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.{DispatcherType, MsgPackagingParam, MsgStoreParam}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.plain.{MsgTransportParam, PlainWebhookDispatcher}
import com.evernym.verity.msgoutbox.outbox.msg_packager.Packagers
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.msg_transporter.Transports
import com.typesafe.config.Config


//one instance gets created for each outbox at the time of outbox actor start
// responsible for dispatching messages to correct dispatcher based on
// available delivery mechanisms and may be delivery attempts
class Dispatcher(outboxActorContext: ActorContext[Outbox.Cmd],
                 config: Config,
                 msgStore: ActorRef[MsgStore.Cmd],
                 packagers: Packagers,
                 transports: Transports) {

  def dispatch(msgId: MsgId, deliveryAttempts: Map[String, MsgDeliveryAttempt]): Unit = {
    currentDispatcher.dispatch(msgId, deliveryAttempts)
  }

  def ack(msgId: MsgId): Unit = {
    currentDispatcher.ack(msgId)
  }

  //NOTE: this is initial logic,
  // it may/will have to change as we integrate/support more scenarios/dispatchers
  def updateDispatcher(walletId: WalletId,
                       senderVerKey: VerKey,
                       comMethods: Map[ComMethodId, ComMethod]): Unit = {
    dispatcherType =
      comMethods
        .find(_._2.typ == COM_METHOD_TYPE_HTTP_ENDPOINT)
        .map { case (comMethodId, comMethod) =>
          new PlainWebhookDispatcher(
            outboxActorContext,
            config,
            comMethodId,
            comMethod,
            MsgStoreParam(msgStore),
            MsgPackagingParam(
              walletId,
              senderVerKey,
              comMethod.recipPackaging,
              comMethod.routePackaging,
              packagers),
            MsgTransportParam(transports.httpTransporter)
          )
        }
  }

  private def currentDispatcher: DispatcherType = dispatcherType.getOrElse(
    throw new RuntimeException("no dispatcher set")
  )

  private var dispatcherType: Option[DispatcherType] = None
}
