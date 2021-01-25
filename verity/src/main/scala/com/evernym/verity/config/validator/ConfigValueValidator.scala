package com.evernym.verity.config.validator

import java.nio.file.{Files, Paths}

import com.evernym.verity.config.CommonConfig.{INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES, KEYSTORE_LOCATION, LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION, LIB_INDY_LIBRARY_DIR_LOCATION, SMS_SVC_ALLOWED_CLIENT_IP_ADDRESSES, VERITY}
import com.evernym.verity.config.validator.base.{ConfigValidator, ConfigValidatorCreator}
import com.evernym.verity.util.SubnetUtilsExt
import com.typesafe.config.{Config, ConfigValueType}

import scala.collection.JavaConverters._

//checks for invalid values (like file path without a file etc) or missing values

object ConfigValueValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new ConfigValueValidator(config)
}

class ConfigValueValidator (val config: Config) extends ConfigValidator {

  override val validationType: String = "general configuration validation"

  override def validateConfig(): Unit = {

    def validateConfigs(atPath: Option[String] = None): Unit = {
      val confsToBeSearched = atPath.map { path =>
        config.withOnlyPath(path)
      }.getOrElse(config)

      confsToBeSearched.entrySet().asScala.foreach { es =>
        if (es.getValue.render().contains("TODO:")) {
          if (es.getValue.valueType() == ConfigValueType.OBJECT) {
            val newPath = Option(atPath.map(_ + ".").getOrElse("") + es.getKey)
            validateConfigs(newPath)
          } else {
            val errMsg = s"invalid config value found: ${es.getValue.render()}"
            throw getValidationFailedExc(es.getValue.origin(), es.getKey, errMsg)
          }
        }
      }
    }

    def checkIfFileExistIfConfigured(confName: String): Unit = {
      getConfigStringOption(confName).foreach { path =>
        if (! Files.exists(Paths.get(path))) {
          val cv = config.getValue(confName)
          val problem = s"no file exists at given path '$path' for config: $confName"
          throw getValidationFailedExc(cv.origin(), confName, problem)
        }
      }
    }


    def checkIfResourceExistIfConfigured(confName: String): Unit = {
      getConfigStringOption(confName).foreach { path =>
        if (! Files.exists(Paths.get(path))) {
          val cv = config.getValue(confName)
          val problem = s"no file exists at given path '$path' for config: $confName"
          throw getValidationFailedExc(cv.origin(), confName, problem)
        }
      }
    }

    def validateAllowedCIDRNotationIpAddresses(): Unit = {
      val confNames = List(INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES, SMS_SVC_ALLOWED_CLIENT_IP_ADDRESSES)
      confNames.foreach { cn =>
        try {
          getConfigListOfStringOption(cn).getOrElse(List.empty).foreach(ip => new SubnetUtilsExt(ip))
        } catch {
          case _: IllegalArgumentException =>
            val cv = config.getValue(cn)
            val problem = s"unable to parse value '${cv.unwrapped()}' for config: $cn"
            throw getValidationFailedExc(cv.origin(), cn, problem)
        }
      }
    }

    def validateAgencyConfigs(): Unit = {
      validateConfigs(Option(VERITY))
      validateAllowedCIDRNotationIpAddresses()
      List(LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION, LIB_INDY_LIBRARY_DIR_LOCATION).foreach { confName =>
        checkIfFileExistIfConfigured(confName)
      }
      checkIfResourceExistIfConfigured(KEYSTORE_LOCATION)
    }

    def validateOtherConfigs(): Unit = {
      validateConfigs(Option("akka"))
      validateConfigs(Option("kamon.environment"))
    }

    validateAgencyConfigs()
    validateOtherConfigs()
  }

}
