package com.evernym.verity.msgoutbox.outbox

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.msgoutbox.{Authentication, ComMethod, ComMethodId, MsgId, VerKey, WalletId}
import com.evernym.verity.msgoutbox.outbox.States.MsgDeliveryAttempt
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.{AccessTokenRefreshers, OAuthAccessTokenRefresher}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.AUTH_TYPE_OAUTH2
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.{OAuthAccessTokenHolder, OAuthWebhookDispatcher}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.{DispatcherType, MsgPackagingParam, MsgStoreParam, MsgTransportParam}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.plain.PlainWebhookDispatcher
import com.evernym.verity.msgoutbox.outbox.msg_packager.MsgPackagers
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.msg_transporter.MsgTransports
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext


//one instance gets created for each outbox at the time of outbox actor start
// responsible for
// * updating dispatcher whenever outbox communication details changes
// * dispatching messages via the current dispatcher set

class Dispatcher(outboxActorContext: ActorContext[Outbox.Cmd],
                 accessTokenRefreshers: AccessTokenRefreshers,
                 config: Config,
                 msgStore: ActorRef[MsgStore.Cmd],
                 msgPackagers: MsgPackagers,
                 msgTransports: MsgTransports,
                 executionContext: ExecutionContext) {

  def dispatch(msgId: MsgId, deliveryAttempts: Map[String, MsgDeliveryAttempt]): Unit = {
    currentDispatcher.dispatch(msgId, deliveryAttempts)
  }

  def ack(msgId: MsgId): Unit = {
    currentDispatcher.ack(msgId)
  }

  //NOTE: this is the initial logic
  // and it may/will have to change as we integrate/support more scenarios/dispatchers
  def updateDispatcher(walletId: WalletId,
                       senderVerKey: VerKey,
                       comMethods: Map[ComMethodId, ComMethod]): Unit = {
    dispatcherType =
      comMethods
        .find(_._2.typ == COM_METHOD_TYPE_HTTP_ENDPOINT)
        .map { case (comMethodId, comMethod) =>
          comMethod.authentication match {
            case None =>
              createPlainWebhookDispatcher(comMethodId, comMethod, walletId, senderVerKey)
            case Some(auth) if auth.`type` == AUTH_TYPE_OAUTH2 =>
              createOAuthWebhookDispatcher(comMethodId, comMethod, walletId, senderVerKey, auth)
            case Some(auth) =>
              throw new RuntimeException("authentication type not supported: " + auth.`type`)
          }
        }
  }

  private def createPlainWebhookDispatcher(comMethodId: ComMethodId,
                                           comMethod: ComMethod,
                                           walletId: WalletId,
                                           senderVerKey: VerKey): DispatcherType = {
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
        msgPackagers),
      MsgTransportParam(msgTransports.httpTransporter)
    )
  }

  private def createOAuthWebhookDispatcher(comMethodId: ComMethodId,
                                           comMethod: ComMethod,
                                           walletId: WalletId,
                                           senderVerKey: VerKey,
                                           auth: Authentication): DispatcherType = {
    val uniqueOAuthAccessTokenHolderId = "oauth-access-token-holder-" + comMethodId

    val oAuthAccessTokenHolder = outboxActorContext.child(uniqueOAuthAccessTokenHolderId) match {
      case None =>
        outboxActorContext.spawn(
          OAuthAccessTokenHolder(
            config,
            auth.data,
            accessTokenRefreshers.refreshers(auth.version)
          ),
          uniqueOAuthAccessTokenHolderId
        )
      case Some(ar: ActorRef[_]) =>
        ar.toClassic ! OAuthAccessTokenHolder.Commands.UpdateParams(
          auth.data,
          OAuthAccessTokenRefresher.getRefresher(auth.version, executionContext)
        )
        ar.asInstanceOf[ActorRef[OAuthAccessTokenHolder.Cmd]]     //TODO: any alternative
      case other => throw new RuntimeException("unexpected type of oauth token holder: " + other)
    }

    new OAuthWebhookDispatcher(
      outboxActorContext,
      oAuthAccessTokenHolder,
      config,
      comMethodId,
      comMethod,
      MsgStoreParam(msgStore),
      MsgPackagingParam(
        walletId,
        senderVerKey,
        comMethod.recipPackaging,
        comMethod.routePackaging,
        msgPackagers),
      MsgTransportParam(msgTransports.httpTransporter)
    )
  }

  private def currentDispatcher: DispatcherType = dispatcherType.getOrElse(
    throw new RuntimeException("no dispatcher set")
  )

  private var dispatcherType: Option[DispatcherType] = None
}
