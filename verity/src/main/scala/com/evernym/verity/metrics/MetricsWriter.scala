package com.evernym.verity.metrics

import java.time.Instant

trait MetricsWriter {

  type TagMap = Map[String, String]

  def gaugeIncrement(name: String, value: Double = 1, tags: TagMap = Map.empty)

  def gaugeUpdate(name: String, value: Double, tags: TagMap = Map.empty)

  def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap = Map.empty): Unit

  def taggedSpan(name: String, start: Instant, tags: TagMap = Map.empty)

  def runWithSpan[T](opName: String, componentName: String, spanType: SpanType = DefaultSpan)(fn: => T): T

  def setup()

  def shutdown()

}
