package com.evernym.verity.config.validator.base

import com.evernym.verity.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.Status.VALIDATION_FAILED
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config.{Config, ConfigException, ConfigObject}

import scala.collection.JavaConverters._

class ConfigReadHelper(val config: Config) extends ConfigReaderHelper

trait ConfigReaderHelper {

  def config: Config

  private def readReqConfig[T](f: String => T, key: String): T = {
    try {
      f(key)
    } catch {
      case _: Missing =>
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"required config not found: $key"))
      case _: ConfigException =>
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"required config not found or has invalid value: $key"))
    }
  }

  private def readOptionalConfig[T](f: String => T, key: String): Option[T] = {
    try {
      Option(f(key))
    } catch {
      case _: Missing => None
    }
  }

  def getConfigStringReq(key: String): String = {
    readReqConfig(config.getString, key)
  }

  def getConfigStringOption(key: String): Option[String] = {
    readOptionalConfig(config.getString, key)
  }

  def getConfigListOfStringReq(key: String): List[String] = {
    readReqConfig(config.getStringList, key).asScala.toList.map(_.trim)
  }

  def getConfigListOfStringOption(key: String): Option[List[String]] = {
    readOptionalConfig(config.getStringList, key).map(_.asScala.toList.map(_.trim))
  }

  def getConfigSetOfStringReq(key: String): Set[String] = {
    readReqConfig(config.getStringList, key).asScala.map(_.trim).toSet
  }

  def getConfigSetOfStringOption(key: String): Option[Set[String]] = {
    readOptionalConfig(config.getStringList, key).map(_.asScala.map(_.trim).toSet)
  }

  def getConfigIntReq(key: String): Int = {
    readReqConfig(config.getInt, key)
  }

  def getConfigIntOption(key: String): Option[Int] = {
    readOptionalConfig(config.getInt, key)
  }

  def getConfigLongReq(key: String): Long = {
    readReqConfig(config.getLong, key)
  }

  def getConfigLongOption(key: String): Option[Long] = {
    readOptionalConfig(config.getLong, key)
  }

  def getConfigDoubleReq(key: String): Double = {
    readReqConfig(config.getDouble, key)
  }

  def getConfigDoubleOption(key: String): Option[Double] = {
    readOptionalConfig(config.getDouble, key)
  }

  def getConfigBooleanOption(key: String): Option[Boolean] = {
    readOptionalConfig(config.getBoolean, key)
  }

  def getConfigBooleanReq(key: String): Boolean = {
    readReqConfig(config.getBoolean, key)
  }

  //helper methods to load from different config
  def getConfigStringReq(fromConfig: Config,key: String): String = {
    readReqConfig(fromConfig.getString, key)
  }

  def getConfigStringOption(fromConfig: Config,key: String): Option[String] = {
    readOptionalConfig(fromConfig.getString, key)
  }

  def getConfigBooleanOption(fromConfig: Config,key: String): Option[Boolean] = {
    readOptionalConfig(fromConfig.getBoolean, key)
  }

  def getConfigIntReq(fromConfig: Config, key: String): Int = {
    readReqConfig(fromConfig.getInt, key)
  }

  def getConfigIntOption(fromConfig: Config, key: String): Option[Int] = {
    readOptionalConfig(fromConfig.getInt, key)
  }

  def getConfigLongReq(fromConfig: Config, key: String): Long = {
    readReqConfig(fromConfig.getLong, key)
  }

  def getConfigLongOption(fromConfig: Config, key: String): Option[Long] = {
    readOptionalConfig(fromConfig.getLong, key)
  }

  def getConfigListOfStringReq(fromConfig: Config, key: String): List[String] = {
    readReqConfig(fromConfig.getStringList, key).asScala.toList.map(_.trim)
  }

  def getConfigListOfStringOption(fromConfig: Config, key: String): Option[List[String]] = {
    readOptionalConfig(fromConfig.getStringList, key).map(_.asScala.toList.map(_.trim))
  }

  def getConfigSetOfStringReq(fromConfig: Config, key: String): Set[String] = {
    getConfigListOfStringReq(fromConfig, key).toSet
  }

  def getConfigOption(key: String): Option[Config] = {
    readOptionalConfig(config.getConfig, key)
  }

  def getConfigOption(fromConfig: Config, key: String): Option[Config] = {
    readOptionalConfig(fromConfig.getConfig, key)
  }

  def getObjectListOption(key: String): Option[Seq[ConfigObject]] = {
    readOptionalConfig(config.getObjectList, key)
      .map(_.asScala)
  }
}