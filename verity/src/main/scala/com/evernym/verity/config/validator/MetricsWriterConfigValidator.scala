package com.evernym.verity.config.validator

import com.evernym.verity.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.Status.VALIDATION_FAILED
import com.evernym.verity.config.CommonConfig
import com.evernym.verity.config.validator.base.{ConfigValidator, ConfigValidatorCreator}
import com.evernym.verity.metrics.MetricsWriter
import com.typesafe.config.Config

object MetricsWriterConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new MetricsWriterConfigValidator(config)
}


class MetricsWriterConfigValidator(val config: Config) extends ConfigValidator {
  override def validationType: String = "metrics writer configuration validation"

  override def validateConfig(): Unit = {

    if (config.hasPath(CommonConfig.METRICS_WRITER)) {
      val className = config.getString(CommonConfig.METRICS_WRITER)
      val mwClass = try {
        Class.forName(className)
      } catch {
        case _: Throwable => throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode,
          Option(s"Class '$className' was not found"))
      }
      if (!classOf[MetricsWriter].isAssignableFrom(mwClass)) {
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode,
          Option(s"Class '$className' should implement 'MetricsWriter' trait"))
      }
    }
  }
}
