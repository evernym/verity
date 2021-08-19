package com.evernym.verity.metrics.backend

import akka.actor.ActorSystem
import com.evernym.verity.metrics._
import kamon.Kamon

import java.time.Instant

class LightbendTelemetryMetricsKamonTraceBackend(system: ActorSystem) extends MetricsBackend {
  override def gaugeIncrement(name: String, value: Long, tags: TagMap): Unit =
    LightbendTelemetryMetricsBackend.gaugeIncrement(
      name,
      value,
      tags
    )(system)

  override def gaugeDecrement(name: String, value: Long, tags: TagMap): Unit =
    LightbendTelemetryMetricsBackend.gaugeDecrement(
      name,
      value,
      tags
    )(system)

  override def gaugeUpdate(name: String, value: Long, tags: TagMap): Unit =
    LightbendTelemetryMetricsBackend.gaugeUpdate(
      name,
      value,
      tags)(system)

  override def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap): Unit =
    LightbendTelemetryMetricsBackend.histogramUpdate(
      name,
      unit,
      value,
      tags
    )(system)

  override def taggedSpan(name: String, start: Instant, tags: TagMap): Unit = KamonMetricsBackend.taggedSpan(
    name,
    start,
    tags
  )

  override def runWithSpan[T](opName: String, componentName: String, spanType: SpanType)(fn: => T): T = {
    KamonMetricsBackend.runWithSpan(
      opName, componentName, spanType
    )(fn)
  }


  override def setup(): Unit = Kamon.init()

  override def shutdown(): Unit = Kamon.stop()
}


