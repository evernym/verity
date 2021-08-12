package com.evernym.verity.msgoutbox.outbox

import akka.persistence.typed.scaladsl
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.typesafe.config.Config

//todo do we still need interfaces?
trait IRetentionCriteria {
  def afterEveryEvents: Int

  def keepSnapshots: Int

  def deleteEventsOnSnapshots: Boolean
}

object IRetentionCriteria { //todo move?
  def convert(irc: IRetentionCriteria): scaladsl.RetentionCriteria = {
    val retentionCriteria = scaladsl.RetentionCriteria.snapshotEvery(numberOfEvents = irc.afterEveryEvents,
      keepNSnapshots = irc.keepSnapshots)

    if (irc.deleteEventsOnSnapshots)
      retentionCriteria.withDeleteEventsOnSnapshot
    else
      retentionCriteria
  }
}

trait IRetryPolicy {
  def maxRetries: Int

  def initialInterval: Long
}

trait IOutboxConfig {
  def batchSize: Int

  def receiveTimeout: Long

  def scheduledJobInterval: Long

  def retentionCriteria: IRetentionCriteria

  def retryPolicy: Map[String, IRetryPolicy]

  def eventEncryptionSalt: String

  def oauthReceiveTimeout: Long
}

object OutboxConfigBuilder { //todo should be moved to Outbox?
  def fromConfig(config: Config): OutboxConfig = {
    val ch = ConfigReadHelper(config)

    val batchSize: Int = ch.getIntOption(OUTBOX_BATCH_SIZE).getOrElse(50)

    val receiveTimeout: Long = ch.getDurationOption(OUTBOX_RECEIVE_TIMEOUT).map(_.toMillis).getOrElse(600000)

    val scheduledJobInterval: Long = ch.getDurationOption(OUTBOX_SCHEDULED_JOB_INTERVAL).map(_.toMillis).getOrElse(5000)

    val rcsAfterEveryEvents: Int = ch.getIntOption(OUTBOX_RETENTION_SNAPSHOT_AFTER_EVERY_EVENTS).getOrElse(100)

    val rcsKeepSnapshots: Int = ch.getIntOption(OUTBOX_RETENTION_SNAPSHOT_KEEP_SNAPSHOTS).getOrElse(2)

    val rcsDeleteEventsSnapshot: Boolean = ch.getBooleanOption(OUTBOX_RETENTION_SNAPSHOT_DELETE_EVENTS_ON_SNAPSHOTS).getOrElse(false)

    val eventEncryptionSalt: String = ch.getStringReq(SALT_EVENT_ENCRYPTION)

    val oauthReceiveTimeout: Long = ch.getDurationOption(OUTBOX_OAUTH_RECEIVE_TIMEOUT).map(_.toMillis).getOrElse(30000)

    val retryPolicy: Map[String, RetryPolicy] = List("webhook", "default").map { name =>
      val maxRetries = ch.getIntOption(s"$OUTBOX.$name.retry-policy.max-retries").getOrElse(5)
      val initialInterval = ch.getDurationOption(s"$OUTBOX.$name.retry-policy.initial-interval").map(_.toMillis).getOrElse(30000L)
      name -> RetryPolicy(maxRetries, initialInterval)
    }.toMap

    OutboxConfig(
      batchSize,
      receiveTimeout,
      scheduledJobInterval,
      RetentionCriteria(rcsAfterEveryEvents, rcsKeepSnapshots, rcsDeleteEventsSnapshot),
      retryPolicy,
      eventEncryptionSalt,
      oauthReceiveTimeout
    )
  }
}