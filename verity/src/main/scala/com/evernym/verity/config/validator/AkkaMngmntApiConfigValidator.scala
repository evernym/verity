package com.evernym.verity.config.validator

import com.evernym.verity.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.Status.VALIDATION_FAILED
import com.evernym.verity.config.CommonConfig.{AKKA_MNGMNT_HTTP, AKKA_MNGMNT_HTTP_API_CREDS, AKKA_MNGMNT_HTTP_ENABLED}
import com.evernym.verity.config.validator.base.{ConfigValidator, ConfigValidatorCreator}
import com.evernym.verity.constants.Constants.YES
import com.typesafe.config.Config
import com.typesafe.config.ConfigException.Missing

import scala.collection.JavaConverters._

object AkkaMngmntApiConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new AkkaMngmntApiConfigValidator(config)
}

class AkkaMngmntApiConfigValidator(val config: Config) extends ConfigValidator {

  override val validationType: String = s"$AKKA_MNGMNT_HTTP config validation"

  override def validateConfig(): Unit =  {
    val isEnabled = try {
      Option(config.getString(AKKA_MNGMNT_HTTP_ENABLED)).map(_.toUpperCase).contains(YES)
    } catch {
      case _: Missing => false
    }
    if (isEnabled) {
      config.getObjectList(AKKA_MNGMNT_HTTP_API_CREDS).asScala.foreach { acs =>
        Set("username", "password").foreach { ek =>
          try {
            acs.toConfig.getString(ek)
          } catch {
            case _ : Missing =>
              throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"'$AKKA_MNGMNT_HTTP_API_CREDS.$ek' config missing"))
          }
        }
      }
    }
  }

}