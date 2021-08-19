package com.evernym.verity.metrics.backend

import akka.actor.ActorSystem
import com.evernym.verity.metrics._
import com.lightbend.cinnamon.akka.CinnamonMetrics
import com.lightbend.cinnamon.meta.Identity
import io.opentracing.Tracer
import io.opentracing.tag.Tags
import io.opentracing.util.GlobalTracer
import kamon.Kamon

import java.time.Instant
import java.time.temporal.ChronoField

class LightbendTelemetryMetricsBackend(system: ActorSystem) extends MetricsBackend {
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

  override def taggedSpan(name: String, start: Instant, tags: TagMap): Unit = {
    val span = GlobalTracer.get.buildSpan(name)

    tags.foldLeft(span) { (s: Tracer.SpanBuilder, t) =>
      s.withTag(t._1, t._2)
    }
    .withStartTimestamp(start.getLong(ChronoField.MICRO_OF_SECOND))
    .start()
    .finish()
  }

  override def runWithSpan[T](opName: String, componentName: String, spanType: SpanType)(fn: => T): T = {
//    val kind = spanType match {
//      case DefaultSpan  => Tags.SPAN_KIND_PRODUCER
//      case ClientSpan   => Tags.SPAN_KIND_CLIENT
//      case InternalSpan => Tags.SPAN_KIND_PRODUCER
//    }

    val parent = GlobalTracer
      .get()
      .activeSpan()

    val span = GlobalTracer
      .get()
      .buildSpan(opName)
      .asChildOf(parent)
//      .withTag(Tags.SPAN_KIND, kind)
      .start()

    try {
      fn
    }
    finally {
      span.finish()
    }
  }


  override def setup(): Unit = Kamon.init()

  override def shutdown(): Unit = Kamon.stop()
}

protected object LightbendTelemetryMetricsBackend {
  def gaugeIncrement(name: String, value: Long, tags: TagMap)(system: ActorSystem): Unit = {
    val (prefix, rest) = nameSplit(name)

    val metric = addIdentity(
      prefix,
      CinnamonMetrics(system)
    )
      .createGaugeLong(rest, tags = tags)

    if (value == 1) {
      metric.increment()
    }
    else if (value > 1) {
      for(_ <- 1L to value) metric.increment()
    }
    else {
      // Unit for zero or negative numbers
    }
  }

  def gaugeDecrement(name: String, value: Long, tags: TagMap)(system: ActorSystem): Unit = {
    val (prefix, rest) = nameSplit(name)

    val metric = addIdentity(
      prefix,
      CinnamonMetrics(system)
    )
    .createGaugeLong(rest, tags = tags)

    if (value == 1) {
      metric.decrement()
    }
    else if (value > 1) {
      for(_ <- 1L to value) metric.decrement()
    }
    else {
      // Unit for zero or negative numbers
    }
  }

  def gaugeUpdate(name: String, value: Long, tags: TagMap)(system: ActorSystem): Unit = {
    val (prefix, rest) = nameSplit(name)

    val metric = addIdentity(
      prefix,
      CinnamonMetrics(system)
    )
      .createGaugeLong(rest, tags = tags)

    metric.set(value)
  }

  def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap)(system: ActorSystem): Unit = {
    val (prefix, rest) = nameSplit(name)

    val metric = addIdentity(
      prefix,
      CinnamonMetrics(system)
    )
      .createRecorder(rest, tags = tags)

    metric.record(value)
  }


  private val asCustomIdentity: Identity = Identity.createFor("as", "").build

  def nameSplit(name: String): (Option[String], String) = {
    if (name == null) {
      (None, "")
    }
    else if(name.startsWith("as.")) {
      (
        Some(name.substring(0, 2)),
        name.substring(3)
      )
    }
    else {
      (None, name)
    }
  }

  private def addIdentity(prefix: Option[String], metrics: CinnamonMetrics): CinnamonMetrics = {
    prefix
    .map{ p =>
      if(p == "as"){
        metrics.metricsFor(asCustomIdentity)
      }
      else
        metrics
    }
    .getOrElse(metrics)
  }
}
