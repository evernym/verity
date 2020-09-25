package com.evernym.verity

import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.core.spi.FilterReply
import com.evernym.verity.config.CommonConfig.LOGGING_IGNORE_FILTER_NAMES
import com.evernym.verity.config.ConfigReadHelper
import com.typesafe.config.ConfigFactory
import org.slf4j.Marker


/**
 * logger filter to decide which logs should be ignored
 */
class IgnoreLoggerFilter extends TurboFilter {

  lazy val configReadHelper = new ConfigReadHelper(ConfigFactory.load())
  lazy val defaultIgnoreLoggerNames: Option[String] =
    configReadHelper.getConfigStringOption(LOGGING_IGNORE_FILTER_NAMES)


  private var loggerNameContainsSet: Set[String] = Set.empty[String]

  def setLoggerNameContains(loggerNameContains: String): Unit = {
    val defaultNames = defaultIgnoreLoggerNames.map(_.split(",").map(_.trim).toSet).getOrElse(Set.empty)
    val fromLogbackXmlFile = loggerNameContains.split(",").map(_.trim).toSet
    loggerNameContainsSet = defaultNames ++ fromLogbackXmlFile
  }

  def decide (marker: Marker, logger: Logger, level: Level, format: String,
              params: Array[AnyRef], t: Throwable): FilterReply = {
    Option(logger) match {
      case Some(lgr) if loggerNameContainsSet.exists(lnc => lgr.getName.contains(lnc))
                      => FilterReply.DENY
      case _          => FilterReply.NEUTRAL
    }
  }
}
