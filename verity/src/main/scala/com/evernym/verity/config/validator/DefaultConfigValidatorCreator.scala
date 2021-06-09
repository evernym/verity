package com.evernym.verity.config.validator

import com.evernym.verity.config.validator.base.ConfigValidatorCreator

object DefaultConfigValidatorCreator {

  def getAllValidatorCreators: List[ConfigValidatorCreator] = List(
    ConfigValueValidator,
    StaleConfigValidator,
    ResourceUsageRuleConfigValidator,
    RequiredConfigValidator,
    OptionalConfigValidator,
    LimitConfigValidator
  )
}