package com.evernym.verity.config.validator.base

import com.evernym.verity.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.Status.VALIDATION_FAILED
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config.{Config, ConfigException, ConfigObject}

import scala.collection.JavaConverters._
import scala.concurrent.duration.{Duration, FiniteDuration}

object ConfigReadHelper extends ConfigReadHelperBase {

  def getBooleanOptionFromConfig(fromConfig: Config, key: String): Option[Boolean] = {
    readOptionalConfig(fromConfig.getBoolean, key)
  }

  def getIntReqFromConfig(fromConfig: Config, key: String): Int = {
    readReqConfig(fromConfig.getInt, key)
  }

  def getIntOptionFromConfig(fromConfig: Config, key: String): Option[Int] = {
    readOptionalConfig(fromConfig.getInt, key)
  }

  def getLongReqFromConfig(fromConfig: Config, key: String): Long = {
    readReqConfig(fromConfig.getLong, key)
  }

  def getLongOptionFromConfig(fromConfig: Config, key: String): Option[Long] = {
    readOptionalConfig(fromConfig.getLong, key)
  }

  def getStringListReqFromConfig(fromConfig: Config, key: String): List[String] = {
    readReqConfig(fromConfig.getStringList, key).asScala.toList.map(_.trim)
  }

  def getStringListOptionFromConfig(fromConfig: Config, key: String): Option[List[String]] = {
    readOptionalConfig(fromConfig.getStringList, key).map(_.asScala.toList.map(_.trim))
  }

  def getStringSetReqFromConfig(fromConfig: Config, key: String): Set[String] = {
    getStringListReqFromConfig(fromConfig, key).toSet
  }

  def getConfigOption(fromConfig: Config, key: String): Option[Config] = {
    readOptionalConfig(fromConfig.getConfig, key)
  }
}

case class ConfigReadHelper(config: Config) extends ConfigReaderHelper


trait ConfigReaderHelper
  extends ConfigReadHelperBase {

  def config: Config

  def getBytesReq(key: String): Long = {
    readReqConfig(config.getBytes, key)
  }

  def getBytesOption(key: String): Option[Long] = {
    readOptionalConfig(config.getBytes, key)
  }

  def getStringReq(key: String): String = {
    readReqConfig(config.getString, key)
  }

  def getStringOption(key: String): Option[String] = {
    readOptionalConfig(config.getString, key)
  }

  def getStringListReq(key: String): List[String] = {
    readReqConfig(config.getStringList, key).asScala.toList.map(_.trim)
  }

  def getStringListOption(key: String): Option[List[String]] = {
    readOptionalConfig(config.getStringList, key).map(_.asScala.toList.map(_.trim))
  }

  def getStringSetReq(key: String): Set[String] = {
    readReqConfig(config.getStringList, key).asScala.map(_.trim).toSet
  }

  def getStringSetOption(key: String): Option[Set[String]] = {
    readOptionalConfig(config.getStringList, key).map(_.asScala.map(_.trim).toSet)
  }

  def getIntReq(key: String): Int = {
    readReqConfig(config.getInt, key)
  }

  def getIntOption(key: String): Option[Int] = {
    readOptionalConfig(config.getInt, key)
  }

  def getLongReq(key: String): Long = {
    readReqConfig(config.getLong, key)
  }

  def getLongOption(key: String): Option[Long] = {
    readOptionalConfig(config.getLong, key)
  }

  def getDoubleReq(key: String): Double = {
    readReqConfig(config.getDouble, key)
  }

  def getDoubleOption(key: String): Option[Double] = {
    readOptionalConfig(config.getDouble, key)
  }

  def getBooleanReq(key: String): Boolean = {
    readReqConfig(config.getBoolean, key)
  }

  def getBooleanOption(key: String): Option[Boolean] = {
    readOptionalConfig(config.getBoolean, key)
  }

  //helper methods to load from different config
  def getStringReq(fromConfig: Config, key: String): String = {
    readReqConfig(fromConfig.getString, key)
  }

  def getStringOption(fromConfig: Config, key: String): Option[String] = {
    readOptionalConfig(fromConfig.getString, key)
  }

  def getConfigOption(key: String): Option[Config] = {
    readOptionalConfig(config.getConfig, key)
  }

  def getObjectListOption(key: String): Option[Seq[ConfigObject]] = {
    readOptionalConfig(config.getObjectList, key)
      .map(_.asScala)
  }

  def getDurationReq(key: String): FiniteDuration = {
    val javaDuration = readReqConfig[java.time.Duration](config.getDuration, key)
    Duration.fromNanos(javaDuration.toNanos)
  }

  def getDurationOption(key: String): Option[FiniteDuration] = {
    val javaDuration = readOptionalConfig(config.getDuration, key)
    javaDuration.map {d =>Duration.fromNanos(d.toNanos) }
  }
}

trait ConfigReadHelperBase {
  protected def readReqConfig[T](f: String => T, key: String): T = {
    try {
      f(key)
    } catch {
      case _: Missing =>
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"required config not found: $key"))
      case _: ConfigException =>
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"required config not found or has invalid value: $key"))
    }
  }

  protected def readOptionalConfig[T](f: String => T, key: String): Option[T] = {
    try {
      Option(f(key))
    } catch {
      case _: Missing => None
    }
  }
}