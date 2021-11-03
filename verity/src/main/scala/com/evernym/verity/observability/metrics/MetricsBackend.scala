package com.evernym.verity.observability.metrics

import java.time.Instant

trait MetricsBackend {

  def gaugeIncrement(name: String, value: Long = 1, tags: TagMap = Map.empty)

  def gaugeDecrement(name: String, value: Long = 1, tags: TagMap = Map.empty)

  def gaugeUpdate(name: String, value: Long, tags: TagMap = Map.empty)

  def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap = Map.empty): Unit

  def taggedSpan(name: String, start: Instant, tags: TagMap = Map.empty)

  def runWithSpan[T](opName: String, componentName: String, spanType: SpanType = DefaultSpan)(fn: => T): T

  def setup()

  def shutdown()

}
