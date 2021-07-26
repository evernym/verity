package com.evernym.verity.metrics

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.evernym.verity.config.ConfigConstants.{METRICS_ENABLED, METRICS_BACKEND}
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.metrics.backend.NoOpMetricsBackend
import com.typesafe.config.Config

class MetricsWriterExtensionImpl(config: Config) extends Extension {

  //TODO: how to make sure this extension is thread safe?

  private val logger = getLoggerByName("MetricsWriterExtension")

  private val metricsWriter = {
    val metricsBackend: MetricsBackend = try {
      if (config.getString(METRICS_ENABLED) == YES) {
        val className = config.getString(METRICS_BACKEND)
        Class.forName(className).getConstructor().newInstance().asInstanceOf[MetricsBackend]
      } else {
        new NoOpMetricsBackend
      }
    } catch {
      case e: Throwable =>
        logger.warn(s"Error occurred during metric writer extension init: ${e.getMessage}. Using fallback NoOpMetrics writer.")
        new NoOpMetricsBackend
    }
    new MetricsWriter(config, metricsBackend)
  }

  def get(): MetricsWriter = metricsWriter

  //allows to update metrics backend at run time
  def updateMetricsBackend(mb: MetricsBackend): Unit = {
    metricsWriter.updateMetricsBackend(mb)
  }

}

object MetricsWriterExtension extends ExtensionId[MetricsWriterExtensionImpl] with ExtensionIdProvider {
  override def lookup = MetricsWriterExtension

  override def createExtension(system: ExtendedActorSystem) = new MetricsWriterExtensionImpl(system.settings.config)
}
