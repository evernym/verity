package com.evernym.verity.metrics

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.metrics.writer.NoOpMetricsWriter
import com.typesafe.config.Config

class MetricsWriterExtensionImpl(config: Config) extends Extension {

  private val logger = getLoggerByName("MetricsWriterExtension")
  private val writerConfigPath = "verity.metrics.writer"

  private var metricsWriter: MetricsWriter = try {
    val className = config.getString(writerConfigPath)
    Class.forName(className).newInstance().asInstanceOf[MetricsWriter]
  } catch {
    case e: Throwable =>
      logger.warn(s"Could not create instance of metric writer: ${e.getMessage}. Using NoOpMetrics writer instead.")
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
