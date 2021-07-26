package com.evernym.verity.metrics

import com.typesafe.config.Config

import java.time.Instant

class MetricsWriter(config: Config, mb: MetricsBackend) {

  private var metricsBackend: MetricsBackend = mb

  def updateMetricsBackend(mb: MetricsBackend): Unit = {
    metricsBackend.shutdown()
    mb.setup()
    metricsBackend = mb
  }

  def gaugeIncrement(name: String, value: Double = 1, tags: TagMap = Map.empty): Unit = {
    metricsBackend.gaugeIncrement(name, value, tags)
  }

  def gaugeDecrement(name: String, value: Double = 1, tags: TagMap = Map.empty): Unit = {
    metricsBackend.gaugeDecrement(name, value, tags)
  }

  def gaugeUpdate(name: String, value: Double, tags: TagMap = Map.empty): Unit = {
    metricsBackend.gaugeUpdate(name, value, tags)
  }

  def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap = Map.empty): Unit = {
    metricsBackend.histogramUpdate(name, unit, value, tags)
  }

  def taggedSpan(name: String, start: Instant, tags: TagMap = Map.empty): Unit = {
    metricsBackend.taggedSpan(name, start, tags)
  }

  def runWithSpan[T](opName: String, componentName: String, spanType: SpanType = DefaultSpan)(fn: => T): T = {
    metricsBackend.runWithSpan(opName, componentName, spanType)(fn)
  }

  def shutdown(): Unit = {
    metricsBackend.shutdown()
  }

}
