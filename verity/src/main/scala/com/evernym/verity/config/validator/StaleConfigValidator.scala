package com.evernym.verity.config.validator

import com.evernym.verity.config.validator.base.{ConfigValidator, ConfigValidatorCreator}
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.typesafe.config.Config
import com.typesafe.config.ConfigException.Missing

//checks any stale configurations and logs a warning if found

object StaleConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new StaleConfigValidator(config)
}

class StaleConfigValidator (val config: Config) extends StaleConfigValidatorBase

trait StaleConfigValidatorBase extends ConfigValidator {

  def staleConfigs: Set[StaleConfig] = Set (

    //removed configs
    StaleConfig("agency.config"),
    StaleConfig("agency.routing"),
    StaleConfig("agency.services.sms-service.external-services.twilio.endpoint"),
    StaleConfig("agency.lib-indy.library-file-location"),
    StaleConfig("agency.lib-indy.ledger.wallet-type"),

    StaleConfig("verity.wallet-api"),
    StaleConfig("kamon.instrumentation.akka.filters.group"),

    //renamed configs
    StaleConfig("verity.cache.key-value-mapper-cache-expiration-time-in-seconds", "verity.cache.key-value-mapper.expiration-time-in-seconds"),
    StaleConfig("verity.cache.agent-config-cache-expiration-time-in-seconds", "verity.cache.agent-config.expiration-time-in-seconds"),
    StaleConfig("verity.cache.agency-detail-cache-expiration-time-in-seconds", "verity.cache.agency-detail.expiration-time-in-seconds"),
    StaleConfig("verity.cache.get-ver-key-cache-expiration-time-in-seconds", "verity.cache.ledger-get-ver-key.expiration-time-in-seconds, verity.cache.wallet-get-ver-key.expiration-time-in-seconds")
  )

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

object StaleConfig {
  def apply(oldPath: String, newPath: String): StaleConfig = StaleConfig(oldPath, Option(newPath))
}
/**
 *
 * @param oldPath the config path which has been removed/unused
 * @param newPath the new config path if path is changed
 */
case class StaleConfig(oldPath: String, newPath: Option[String]=None)
