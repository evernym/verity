package com.evernym.verity.observability.metrics.backend

import akka.actor.ActorSystem
import com.evernym.verity.observability.metrics.TagMap
import com.evernym.verity.observability.metrics.{ClientSpan, DefaultSpan, InternalSpan, MetricsBackend, MetricsUnit, SpanType}
import kamon.Kamon
import kamon.tag.TagSet

import java.time.Instant

class KamonMetricsBackend(system: ActorSystem) extends MetricsBackend {

  override def gaugeIncrement(name: String, value: Long, tags: TagMap): Unit = {
    Kamon.gauge(name).withTags(TagSet.from(tags)).increment(value)
  }

  override def gaugeDecrement(name: String, value: Long, tags: TagMap): Unit = {
    Kamon.gauge(name).withTags(TagSet.from(tags)).decrement(value)
  }

  override def gaugeUpdate(name: String, value: Long, tags: TagMap): Unit = {
    Kamon.gauge(name).withTags(TagSet.from(tags)).update(value)
  }

  override def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap): Unit = {
    Kamon.histogram(name).withTags(TagSet.from(tags)).record(value)
  }


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

  override def setup(): Unit = {
    Kamon.init(system.settings.config.withOnlyPath("kamon"))
  }

  override def shutdown(): Unit = Kamon.stop()

}

object KamonMetricsBackend {
  def taggedSpan(name: String, start: Instant, tags: TagMap): Unit = {
    val span = Kamon.spanBuilder(name).start(start)
    val taggedSpan = tags.foldLeft(span) { case (sp, mt) =>
      sp.tagMetrics(mt._1, mt._2)
    }
    taggedSpan.finish(Instant.now())
  }

  def runWithSpan[T](opName: String, componentName: String, spanType: SpanType)(fn: => T): T = {
    val spanBuilder = spanType match {
      case DefaultSpan  => Kamon.spanBuilder(opName)
      case ClientSpan   => Kamon.clientSpanBuilder(opName, componentName)
      case InternalSpan => Kamon.internalSpanBuilder(opName, componentName)
    }
    Kamon.runWithSpan(spanBuilder.start())(fn)
  }
}
