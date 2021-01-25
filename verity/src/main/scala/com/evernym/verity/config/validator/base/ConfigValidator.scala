package com.evernym.verity.config.validator.base

import com.evernym.verity.Exceptions
import com.evernym.verity.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.Status._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config._
import com.typesafe.scalalogging.Logger


trait ConfigValidator extends ConfigReaderHelper {

  val logger: Logger = getLoggerByClass(classOf[ConfigValidator])

  def validationType: String

  def validateConfig(): Unit

  def performConfigValidation(): Unit = {
    logger.debug(validationType + " started...")
    validateConfig()
    logger.debug(validationType + " finished")
  }

  def getValidationFailedExc(origin: ConfigOrigin, path: String, problem: String): ConfigLoadingFailedException = {
    val sb = new StringBuilder
    sb.append("origin: ")
    sb.append(origin.description)
    sb.append(", path: ")
    sb.append(path)
    sb.append(", error: ")
    sb.append(problem)
    new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(sb.toString()))
  }
}


case class DepConfDetail(confName: String, confValue: Option[String] = None, caseSensitive: Boolean=true)

case class ConfDetail(confName: String,
                      allowedValues: Set[String] = Set.empty,
                      unAllowedValues: Set[String] = Set.empty,
                      depConfDetail: Option[DepConfDetail] = None)


trait ConfigValidatorBase extends ConfigValidator {
  def configsToBeValidated(): Set[ConfDetail]
  def required: Boolean

  def getOptConf(confName: String): Option[String] = {
    try {
      Option(config.getAnyRef(confName).toString)
    } catch {
      case _: Missing => None
    }
  }

  def checkConf(required: Boolean, confName: String, allowedValues: Set[String] = Set.empty,
                unAllowedValues: Set[String] = Set.empty): Unit = {
    try {
      val av = config.getAnyRef(confName)

      if (allowedValues.nonEmpty && ! allowedValues.contains(av.toString)) {
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option("config value is not as expected " +
          s"(config name: '$confName', actual conf value: '$av', allowed values: '${allowedValues.mkString(", ")}')"))
      }

      if (unAllowedValues.nonEmpty && unAllowedValues.contains(av.toString)) {
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option("config value is not as expected " +
          s"(config name: '$confName', actual conf value: '$av', un allowed values: '${unAllowedValues.mkString(", ")}' " +
          "[NOTE: it might be default value which you don't see in your configuration, " +
          "in that case just add this new configuration with some value other than un allowed values])"))
      }

    } catch {
      case _: Missing if required =>
        val ev = if (allowedValues.nonEmpty) s" (allowed values: ${allowedValues.mkString(", ")})" else ""
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"required config missing: $confName$ev"))
      case _: Missing =>
        //it means this configuration was optional (as required is false)
        // and nothing needs to be done
    }
  }

  override def validateConfig(): Unit = {

    configsToBeValidated().foreach { c =>
      c.depConfDetail match {
        case Some(dc: DepConfDetail) =>
          val actualDepConfValue = getOptConf(dc.confName)
          val givenDepConfValue = dc.confValue
          (actualDepConfValue, givenDepConfValue) match {
            case (Some(_), None) =>
              checkConf(required, c.confName, c.allowedValues, c.unAllowedValues)
            case (Some(adcv), Some(gdcv)) if dc.caseSensitive && adcv == gdcv =>
              checkConf(required, c.confName, c.allowedValues, c.unAllowedValues)
            case (Some(adcv), Some(gdcv)) if ! dc.caseSensitive && adcv.toUpperCase == gdcv.toUpperCase =>
              checkConf(required, c.confName, c.allowedValues, c.unAllowedValues)
            case _ => //nothing to do as dependent conf itself is not present
          }
        case None =>
          checkConf(required, c.confName, c.allowedValues, c.unAllowedValues)
      }
    }
  }
}


trait ConfigValidatorCreator {
  def create(config: Config): ConfigValidator
}

class ConfigValidatorHelper(givenConfig: Option[Config]=None) {

  val config: Config = givenConfig.getOrElse{
    try {
      ConfigFactory.load()
    } catch {
      case e: Exception =>
        throw new ConfigLoadingFailedException(CONFIG_LOADING_FAILED.statusCode, Option(s"error while loading config (${Exceptions.getErrorMsg(e)})"))
    }
  }

  def getAllValidators(validatorCreators: List[ConfigValidatorCreator]): List[ConfigValidator] = {
    validatorCreators.map(_.create(config))
  }
}