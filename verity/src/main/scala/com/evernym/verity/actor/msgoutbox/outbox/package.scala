package com.evernym.verity.actor.msgoutbox

import com.evernym.verity.storage_services.StorageAPI

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

package object outbox {

  type RelId = String
  type DestId = String
  type ComMethodId = String
  type OutboxId = String
  type WalletId = String
  type MsgId = String
  type VerKey = String
  type DID = String
  type ParticipantId = String

  case class StorageApiParam(bucketName: String, storageAPI: StorageAPI)

  case class RetryParam(failedAttemptCount: Int = 0,
                        maxRetries: Int,
                        initialInterval: FiniteDuration) {

    def withFailedAttemptIncremented: RetryParam = copy(failedAttemptCount = failedAttemptCount + 1)
    def isRetryAttemptsLeft: Boolean = failedAttemptCount < maxRetries
    def isRetryAttemptExhausted: Boolean = ! isRetryAttemptsLeft
    def nextInterval: FiniteDuration = {
      val incrementBy = initialInterval.length*(failedAttemptCount+1) + Random.nextInt(5)
      initialInterval.plus(FiniteDuration(incrementBy, initialInterval.unit))
    }
  }
}
