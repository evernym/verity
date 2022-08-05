package com.evernym.verity.config

import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, ConfigLoadingFailedException}
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.actor.appStateManager.AppStateUpdateAPI.handleError
import com.evernym.verity.actor.appStateManager.{ErrorEvent, SeriousSystemError}
import com.evernym.verity.config.validator.DefaultConfigValidatorCreator
import com.evernym.verity.config.validator.base.{ConfigReaderHelper, ConfigValidatorCreator, ConfigValidatorHelper}
import com.evernym.verity.util2.Exceptions
import com.typesafe.config._
import com.typesafe.scalalogging.Logger


/**
 * a wrapper around typesafe config with some utility functions
 */
trait AppConfig extends ConfigReaderHelper {

  val logger: Logger = getLoggerByClass(classOf[AppConfig])
  var validatorCreators: List[ConfigValidatorCreator] = DefaultConfigValidatorCreator.getAllValidatorCreators

  var config : Config

  init()

  private def loadConfig(): Config = {
    validatedConfig()
  }

  def validatedConfig(config: Option[Config]=None): Config = {
    val configValidatorHelper = new ConfigValidatorHelper(config)
    val validators = configValidatorHelper.getAllValidators(validatorCreators)
    validators.foreach { v =>
      v.performConfigValidation()
    }
    configValidatorHelper.config
  }

  def init(confValidators: List[ConfigValidatorCreator]=List.empty): AppConfig = {
    try {
      if (Option(config).isEmpty) {
        if (confValidators.nonEmpty) {
          validatorCreators = confValidators
        }
        config = loadConfig()
      }
      this
    } catch {
      case e: ConfigException =>
        val errorMsg = s"error while loading config (${Exceptions.getErrorMsg(e)})"
        val ex = new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(errorMsg))
        handleError(ErrorEvent(SeriousSystemError, CONTEXT_CONFIG_LOADING, ex, Option(errorMsg)))
        throw ex
      case e: BadRequestErrorException =>
        val errorMsg = s"error while loading config (${e.respMsg.getOrElse(Exceptions.getErrorMsg(e))})"
        val ex = new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(errorMsg))
        handleError(ErrorEvent(SeriousSystemError, CONTEXT_CONFIG_LOADING, ex, Option(errorMsg)))
        throw ex
      case e: Exception =>
        val errorMsg = s"error while loading config (${Exceptions.getErrorMsg(e)})"
        val ex = new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(errorMsg))
        handleError(ErrorEvent(SeriousSystemError, CONTEXT_CONFIG_LOADING, ex, Option(errorMsg)))
        throw ex
    }
  }

  def reload(): Unit = {
    try {
      ConfigFactory.invalidateCaches()
      config = loadConfig()
    } catch {
      case e: BadRequestErrorException =>
        val errorMsg = "could not load config"
        logger.error(errorMsg, (LOG_KEY_ERR_MSG, e.respMsg.getOrElse(Exceptions.getErrorMsg(e))))
        throw new BadRequestErrorException(VALIDATION_FAILED.statusCode, Option(errorMsg))

      case e: Exception =>
        val errorMsg = "could not load config"
        logger.error(errorMsg, (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
        throw new BadRequestErrorException(VALIDATION_FAILED.statusCode, Option(errorMsg))
    }
  }

  def getLoadedConfig: Config = config

  def setConfig(conf: Config): Unit = config = validatedConfig(Option(conf))

  def DEPRECATED_setConfigWithoutValidation(conf: Config): Unit = config = conf

}

class AppConfigWrapper extends AppConfig {
  override var config: Config = _
}