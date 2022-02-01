package com.evernym.verity.config.validator

import com.evernym.verity.config.validator.base.{ConfigValidator, ConfigValidatorCreator}
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.typesafe.config.Config
import com.typesafe.config.ConfigException.Missing

//checks any stale (removed or renamed) configurations and logs info message if found

object StaleConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new StaleConfigValidator(config)
}

class StaleConfigValidator (val config: Config) extends StaleConfigValidatorBase

trait StaleConfigValidatorBase extends ConfigValidator {

  private def staleConfigs: Set[StaleConfig] = removedConfig ++ renamedConfig

  private def removedConfig: Set[StaleConfig] = Set (
    "agency.config",
    "agency.routing",
    "agency.services.sms-service.external-services.twilio.endpoint",
    "agency.lib-indy.library-file-location",
    "agency.lib-indy.ledger.wallet-type",

    "verity.wallet-api",
    "kamon.instrumentation.akka.filters.group",

    "verity.timeout.sms-service-ask-timeout-in-seconds",
    "verity.timeout.service-shutdown-timeout-in-seconds",
    "verity.user-agent-pairwise-watcher",

    "verity.services.sms-service.send-via-local-agency",
    "verity.services.sms-service.endpoint",
    "verity.services.sms-service.allowed-client-ip-addresses",
    "verity.app-state-manager.state.draining",

    "verity.item-manager",
    "verity.item-container",

  ).map(RemovedConfig)

  private def renamedConfig: Set[StaleConfig] = Set (
    "verity.cache.key-value-mapper-cache-expiration-time-in-seconds" -> "verity.cache.key-value-mapper.expiration-time-in-seconds",
    "verity.cache.agent-config-cache-expiration-time-in-seconds" -> "verity.cache.agent-config.expiration-time-in-seconds",
    "verity.cache.agency-detail-cache-expiration-time-in-seconds" -> "verity.cache.agency-detail.expiration-time-in-seconds",
    "verity.cache.get-ver-key-cache-expiration-time-in-seconds" -> "verity.cache.ledger-get-ver-key.expiration-time-in-seconds, verity.cache.wallet-get-ver-key.expiration-time-in-seconds",
    "verity.timeout.actor-ref-resolve-timeout-in-seconds" -> "verity.timeout.general-actor-ref-resolve-timeout-in-seconds",
  ).map{ case (on, nw) => RenamedConfig(on, nw)}

  override val validationType: String = "stale configuration checking"

  override def validateConfig(): Unit = {
    staleConfigs.foreach { sc =>
      try {
        val cv = config.getValue(sc.oldPath)
        val staleConfigDetail =
          "file name: " + cv.origin.filename +
            ", line no: " + cv.origin.lineNumber +
            ", path: " + sc.oldPath +
            sc.newPath.map(np => ", new path(s): " + np).getOrElse("")
        logger.info("stale configuration found (it can be removed)", (LOG_KEY_ERR_MSG, staleConfigDetail))
      } catch {
        case _: Missing =>
      }
    }
  }
}


trait StaleConfig {
  def oldPath: String
  def newPath: Option[String]
}

/**
 *
 * @param oldPath the config path which has been removed
 */
case class RemovedConfig(oldPath: String) extends StaleConfig {
  override def newPath: Option[String] = None
}

object RenamedConfig {
  def apply(oldPath: String, newPath: String): RenamedConfig = RenamedConfig(oldPath, Option(newPath))
}
/**
 *
 * @param oldPath the config path which is renamed
 * @param newPath the new config path
 */
case class RenamedConfig(oldPath: String, newPath: Option[String]) extends StaleConfig
