package com.evernym.verity.config.validator

import com.evernym.verity.config.CommonConfig.AKKA_HTTP_MSG_SENDING_SVC_API_TYPE
import com.evernym.verity.config.validator.base.{ConfDetail, ConfigValidator, ConfigValidatorBase, ConfigValidatorCreator}
import com.typesafe.config.Config

//only validates the config if it is configured

object OptionalConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new OptionalConfigValidator(config)
}

class OptionalConfigValidator (val config: Config) extends OptionalConfigValidatorBase

trait OptionalConfigValidatorBase extends ConfigValidatorBase {

  override def configsToBeValidated(): Set[ConfDetail] = Set(
    ConfDetail(AKKA_HTTP_MSG_SENDING_SVC_API_TYPE, Set("connection-level-flow-api", "request-level-flow-api", "request-level-future-api"))
  )

  override val validationType: String = "optional configuration validation"
  override val required = false
}