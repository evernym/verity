package com.evernym.verity.observability.metrics

import java.time.Instant
import scala.util.matching.Regex


class MetricsWriter(_metricsBackend: MetricsBackend, _filters: Set[Regex]=Set.empty) {

  private var filters: Set[Regex] = _filters

  private var metricsBackend: MetricsBackend = {
    _metricsBackend.setup()
    _metricsBackend
  }

  def updateMetricsBackend(newBackend: MetricsBackend): Unit = {
    newBackend.setup()
    metricsBackend.shutdown()
    metricsBackend = newBackend
  }

  def updateFilters(newFilters: Set[Regex]): Unit = {
    filters = newFilters
    metricsBackend.shutdown()
    metricsBackend.setup()
  }

  def gaugeIncrement(name: String, value: Long = 1, tags: TagMap = Map.empty): Unit =
    withFilterCheck(name) {
      metricsBackend.gaugeIncrement(name, value, tags)
    }

  def gaugeDecrement(name: String, value: Long = 1, tags: TagMap = Map.empty): Unit =
    withFilterCheck(name) {
      metricsBackend.gaugeDecrement(name, value, tags)
    }

  def gaugeUpdate(name: String, value: Long, tags: TagMap = Map.empty): Unit =
    withFilterCheck(name) {
      metricsBackend.gaugeUpdate(name, value, tags)
    }

  def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap = Map.empty): Unit =
    withFilterCheck(name) {
      metricsBackend.histogramUpdate(name, unit, value, tags)
    }

  private def withFilterCheck(name: String)(f: => Unit): Unit = {
    if (! filters.exists(_.pattern.matcher(name).matches())) {
      f
    }
  }

  def taggedSpan(name: String, start: Instant, tags: TagMap = Map.empty): Unit = {
    metricsBackend.taggedSpan(name, start, tags)
  }

  def runWithSpan[T](opName: String, componentName: String, spanType: SpanType = DefaultSpan)(fn: => T): T = {
    metricsBackend.runWithSpan(opName, componentName, spanType)(fn)
  }

  def shutdown(): Unit = metricsBackend.shutdown()
}
