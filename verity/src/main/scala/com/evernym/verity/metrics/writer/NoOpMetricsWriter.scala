package com.evernym.verity.metrics.writer

import com.evernym.verity.metrics.{MetricsUnit, MetricsWriter, SpanType}

import java.time.Instant

class NoOpMetricsWriter extends MetricsWriter{

  override def gaugeIncrement(name: String, value: Double, tags: TagMap): Unit = ()

  override def gaugeDecrement(name: String, value: Double, tags: TagMap): Unit = ()

  override def gaugeUpdate(name: String, value: Double, tags: TagMap): Unit = ()

  override def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap): Unit = ()


  override def taggedSpan(name: String, start: Instant, tags: TagMap): Unit = ()

  override def runWithSpan[T](opName: String, componentName: String, spanType: SpanType)(fn: => T): T = fn

  override def setup(): Unit = ()

  override def shutdown(): Unit = ()
}
