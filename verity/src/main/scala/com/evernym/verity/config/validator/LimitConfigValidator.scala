package com.evernym.verity.config.validator

import com.evernym.verity.util2.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.util2.Status.VALIDATION_FAILED
import com.evernym.verity.actor.agent.msghandler.AgentMsgProcessor.{PACKED_MSG_LIMIT, REST_LIMIT}
import com.evernym.verity.config.CommonConfig.MSG_LIMITS
import com.evernym.verity.config.validator.base.{ConfigValidator, ConfigValidatorCreator}
import com.typesafe.config.{Config, ConfigValueType}

object LimitConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new LimitConfigValidator(config)
}

class LimitConfigValidator(val config: Config) extends ConfigValidator {
  override val validationType: String = "message limits configuration validation"

  override def validateConfig(): Unit = {

    def validateParam(msgFamilyName: String, limitName: String): Unit = {
      val path = s"$MSG_LIMITS.$msgFamilyName.$limitName"
      val hasRestLimit = config.hasPath(path)
      if (hasRestLimit) {
        if (config.getValue(path).valueType() != ConfigValueType.NUMBER) {
          throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"Limit config $msgFamilyName.$limitName should be a number"))
        }
      } else {
        logger.warn(s"Limit config $msgFamilyName does not have '$limitName' value")
      }
    }

    if (config.hasPath(MSG_LIMITS)) {
      val limitConfig = config.getObject(MSG_LIMITS)
      limitConfig.forEach { case (msgFamilyName, conf) =>
        if (conf.valueType() != ConfigValueType.OBJECT) {
          throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"Limit config '$msgFamilyName' should be an object"))
        }
        validateParam(msgFamilyName, REST_LIMIT)
        validateParam(msgFamilyName, PACKED_MSG_LIMIT)
      }
    }
  }
}
