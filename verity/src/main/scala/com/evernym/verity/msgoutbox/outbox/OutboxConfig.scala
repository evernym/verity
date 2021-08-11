package com.evernym.verity.msgoutbox.outbox

import akka.persistence.typed.scaladsl.RetentionCriteria
import com.evernym.verity.config.ConfigConstants.SALT_EVENT_ENCRYPTION
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions, ConfigValueFactory}

import scala.concurrent.duration.{FiniteDuration, SECONDS}

trait OutboxConfig {
  def batchSize: Int

  def receiveTimeout: FiniteDuration

  def scheduledJobInterval: FiniteDuration

  def retentionCriteria: RetentionCriteria

  def retryPolicyMaxRetries(comMethodType: String): Int

  def retryPolicyInitialInterval(comMethodType: String): FiniteDuration

  def eventEncryptionSalt: String

  def oauthReceiveTimeout: FiniteDuration
}

// todo move hardcoded strings to config constants
class OutboxConfigImpl(val configStr: String) extends OutboxConfig { //todo
  private val configReader = ConfigReadHelper(ConfigFactory.parseString(configStr))

  override def batchSize: Int = configReader
    .getIntOption("verity.outbox.batch-size")
    .getOrElse(50)

  override def receiveTimeout: FiniteDuration = configReader
    .getDurationOption("verity.outbox.receive-timeout")
    .getOrElse(FiniteDuration(600, SECONDS))

  override def scheduledJobInterval: FiniteDuration = configReader
    .getDurationOption("verity.outbox.scheduled-job-interval")
    .getOrElse(FiniteDuration(5, SECONDS))

  override def retentionCriteria: RetentionCriteria = {
    val afterEveryEvents = configReader
      .getIntOption("verity.outbox.retention-criteria.snapshot.after-every-events")
      .getOrElse(100)
    val keepSnapshots = configReader
      .getIntOption("verity.outbox.retention-criteria.snapshot.keep-snapshots")
      .getOrElse(2)
    val deleteEventOnSnapshot = configReader
      .getBooleanOption("verity.outbox.retention-criteria.snapshot.delete-events-on-snapshots")
      .getOrElse(true)
    val retentionCriteria = RetentionCriteria.snapshotEvery(numberOfEvents = afterEveryEvents, keepNSnapshots = keepSnapshots)

    if (deleteEventOnSnapshot)
      retentionCriteria.withDeleteEventsOnSnapshot
    else
      retentionCriteria
  }

  override def retryPolicyMaxRetries(comMethodType: String): Int = configReader
    .getIntOption(s"verity.outbox.$comMethodType.retry-policy.max-retries")
    .getOrElse(5)

  override def retryPolicyInitialInterval(comMethodType: String): FiniteDuration = configReader
    .getDurationOption(s"verity.outbox.$comMethodType.retry-policy.initial-interval")
    .getOrElse(FiniteDuration(5, SECONDS))

  override def eventEncryptionSalt: String = configReader.getStringReq(SALT_EVENT_ENCRYPTION)

  override def oauthReceiveTimeout: FiniteDuration = configReader
    .getDurationOption("verity.outbox.oauth-token-holder.receive-timeout")
    .getOrElse(FiniteDuration(30, SECONDS))
}

// todo decide where should we store default values
//       it's better to move defaults check to this function
object OutboxConfigImpl {
  def fromConfig(config: Config): OutboxConfigImpl = {
    var outboxConfig = ConfigFactory.empty()

    val salt = config.getString(SALT_EVENT_ENCRYPTION)
    outboxConfig = outboxConfig.withValue(SALT_EVENT_ENCRYPTION, ConfigValueFactory.fromAnyRef(salt))

    if (config.hasPath("verity.outbox")){
      val c = config.withOnlyPath("verity.outbox")
      outboxConfig = outboxConfig.withFallback(c)
    }



    new OutboxConfigImpl(outboxConfig.root().render(ConfigRenderOptions.concise())) // Todo decide how to store this
  }
}
