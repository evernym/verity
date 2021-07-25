package com.evernym.verity.metrics.writer

import com.evernym.verity.metrics._
import kamon.Kamon
import kamon.tag.TagSet

import java.time.Instant

class KamonMetricsWriter extends MetricsWriter {

  override def gaugeIncrement(name: String, value: Double, tags: TagMap): Unit = {
    Kamon.gauge(name).withTags(TagSet.from(tags)).increment(value)
  }

  override def gaugeUpdate(name: String, value: Double, tags: TagMap): Unit = {
    Kamon.gauge(name).withTags(TagSet.from(tags)).update(value)
  }

  override def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap): Unit = {
    Kamon.histogram(name).withTags(TagSet.from(tags)).record(value)
  }


  override def taggedSpan(name: String, start: Instant, tags: TagMap): Unit = {
    val span = Kamon.spanBuilder(name).start(start)
    val taggedSpan = tags.foldLeft(span) { case (sp, mt) =>
      sp.tagMetrics(mt._1, mt._2)
    }
    taggedSpan.finish(Instant.now())
  }

  override def runWithSpan[T](opName: String, componentName: String, spanType: SpanType)(fn: => T): T = {
    val spanBuilder = spanType match {
      case DefaultSpan => Kamon.spanBuilder(opName)
      case ClientSpan => Kamon.clientSpanBuilder(opName, componentName)
      case InternalSpan => Kamon.internalSpanBuilder(opName, componentName)
    }
    Kamon.runWithSpan(spanBuilder.start())(fn)
  }

  override def setup(): Unit = Kamon.init()

  override def shutdown(): Unit = Kamon.stop()

}
