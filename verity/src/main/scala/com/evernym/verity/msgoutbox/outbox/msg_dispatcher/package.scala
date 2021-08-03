package com.evernym.verity.msgoutbox.outbox

import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.msgoutbox.outbox.msg_packager.MsgPackagers
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter
import com.evernym.verity.msgoutbox.{ComMethodId, MsgId, RecipPackaging, RoutePackaging, WalletId}

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

package object msg_dispatcher {

  case class DispatchParam(msgId: MsgId,
                           comMethodId: ComMethodId,
                           retryParam: Option[RetryParam],
                           replyTo: ActorRef[Outbox.Cmd])

  case class RetryParam(failedAttemptCount: Int = 0,
                        maxRetries: Int,
                        initialInterval: FiniteDuration) {

    def withFailedAttemptIncremented: RetryParam = copy(failedAttemptCount = failedAttemptCount + 1)

    def isRetryAttemptsLeft: Boolean = failedAttemptCount < maxRetries

    def isRetryAttemptExhausted: Boolean = !isRetryAttemptsLeft

    def nextInterval: FiniteDuration = {
      val incrementBy = initialInterval.length * (failedAttemptCount + 1) + Random.nextInt(5)
      initialInterval.plus(FiniteDuration(incrementBy, initialInterval.unit))
    }
  }

  case class MsgStoreParam(msgStore: ActorRef[MsgStore.Cmd])

  case class MsgPackagingParam(walletId: WalletId,
                               senderVerKey: VerKeyStr,
                               recipPackaging: Option[RecipPackaging],
                               routePackaging: Option[RoutePackaging],
                               msgPackagers: MsgPackagers)

  case class WebhookParam(url: String)

  case class MsgTransportParam(httpTransporter: Behavior[HttpTransporter.Cmd])
}
