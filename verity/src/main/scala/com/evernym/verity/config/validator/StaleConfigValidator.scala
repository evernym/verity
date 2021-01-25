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

  def staleConfigs: Set[String] = Set (
    "agency.config",
    "agency.routing",
    "agency.services.sms-service.external-services.twilio.endpoint",
    "agency.lib-indy.library-file-location",
    "agency.lib-indy.ledger.wallet-type"
  )

  override val validationType: String = "stale configuration checking"

  override def validateConfig(): Unit = {
    staleConfigs.foreach { sc =>
      try {
        val cv = config.getValue(sc)
        val staleConfigDetail =
          "file name: " + cv.origin.filename +
            ", line no: " + cv.origin.lineNumber +
            ", path: " + sc
        logger.warn("stale configuration found (it can be removed)", (LOG_KEY_ERR_MSG, staleConfigDetail))
      } catch {
        case _: Missing =>
      }
    }
  }
}
