package com.evernym.verity.metrics

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.evernym.verity.config.CommonConfig.{METRICS_ENABLED, METRICS_WRITER}
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.metrics.writer.NoOpMetricsWriter
import com.typesafe.config.Config

class MetricsWriterExtensionImpl(config: Config) extends Extension {

  private val logger = getLoggerByName("MetricsWriterExtension")

  private var metricsWriter: MetricsWriter = try {
    if (config.getString(METRICS_ENABLED) == YES) {
      val className = config.getString(METRICS_WRITER)
      val writer = Class.forName(className).getConstructor().newInstance().asInstanceOf[MetricsWriter]
      writer.setup()
      writer
    } else {
      new NoOpMetricsWriter
    }
  } catch {
    case e: Throwable =>
      logger.warn(s"Error occurred during metric writer extension init: ${e.getMessage}. Using fallback NoOpMetrics writer.")
      new NoOpMetricsWriter
  }

  def get(): MetricsWriter = metricsWriter

  def set(mw: MetricsWriter): Unit = {
    metricsWriter.shutdown()
    metricsWriter = mw
    metricsWriter.setup()
  }

}

object MetricsWriterExtension extends ExtensionId[MetricsWriterExtensionImpl] with ExtensionIdProvider {
  override def lookup = MetricsWriterExtension

  override def createExtension(system: ExtendedActorSystem) = new MetricsWriterExtensionImpl(system.settings.config)
}
