package com.evernym.verity.metrics

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.evernym.verity.metrics.writer.NoOpMetricsWriter

class MetricsWriterExtensionImpl(private var metricsWriter: MetricsWriter = new NoOpMetricsWriter) extends Extension {

  def get(): MetricsWriter = metricsWriter

  def set(mw: MetricsWriter): Unit = {
    metricsWriter.shutdown()
    metricsWriter = mw
    metricsWriter.setup()
  }

}

object MetricsWriterExtension extends ExtensionId[MetricsWriterExtensionImpl] with ExtensionIdProvider {
  override def lookup = MetricsWriterExtension

  override def createExtension(system: ExtendedActorSystem) = new MetricsWriterExtensionImpl
}
