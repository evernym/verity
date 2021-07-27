package com.evernym.verity.metrics

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.evernym.verity.config.ConfigConstants.{METRICS_BACKEND, METRICS_ENABLED}
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.metrics.backend.NoOpMetricsBackend
import com.typesafe.config.Config

import scala.util.matching.Regex

class MetricsWriterExtensionImpl(config: Config) extends Extension {

  private val logger = getLoggerByName("MetricsWriterExtension")

  private val metricsWriter = {
    try {
      val metricsBackend: MetricsBackend = try {
        if (config.getString(METRICS_ENABLED) == YES) {
          val className = config.getString(METRICS_BACKEND)
          Class.forName(className).getConstructor().newInstance().asInstanceOf[MetricsBackend]
        } else {
          new NoOpMetricsBackend
        }
      } catch {
        case e: Throwable =>
          logger.warn(s"Error occurred during metric backend init: ${e.getMessage}. Using fallback NoOpMetrics writer.")
          new NoOpMetricsBackend
      }
      new MetricsWriter(metricsBackend, getExcludeFilters(config))
    } catch {
      case e: Throwable =>
        logger.warn(s"Error occurred during metric writer extension init: ${e.getMessage}")
        throw e
    }
  }

  def get(): MetricsWriter = metricsWriter

  //allows to update metrics backend at run time
  def updateMetricsBackend(mb: MetricsBackend): Unit = {
    metricsWriter.updateMetricsBackend(mb)
  }

  //allows to update metrics filters at run time
  def updateFilters(config: Config): Unit = {
    metricsWriter.updateFilters(getExcludeFilters(config))
  }

  private def getExcludeFilters(config: Config): Set[Regex] =
    ConfigReadHelper(config)
      .getStringSetOption("verity.metrics.writer.exclude")
      .getOrElse(Set.empty)
      .map(_.r)
}

object MetricsWriterExtension extends ExtensionId[MetricsWriterExtensionImpl] with ExtensionIdProvider {
  override def lookup = MetricsWriterExtension

  override def createExtension(system: ExtendedActorSystem) = new MetricsWriterExtensionImpl(system.settings.config)
}
