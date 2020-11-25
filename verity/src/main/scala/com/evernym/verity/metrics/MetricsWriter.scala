package com.evernym.verity.metrics

import kamon.Kamon
import kamon.metric.{MeasurementUnit, Metric}
import kamon.tag.TagSet

/**
 * metrics api to add/update metrics values
 */
object MetricsWriter {
  lazy val gaugeApi: GaugeApi = GaugeApiImpl
  lazy val histogramApi: HistogramApi = HistogramApiImpl
}

trait GaugeApi {
  def increment(name: String): Unit
  def increment(name: String, duration: Long): Unit
  def incrementWithTags(name: String, tags: Map[String, String] = Map.empty)
  def updateWithTags(name: String, value: Long, tags: Map[String, String] = Map.empty)
  def update(name: String, value: Long)
}


object GaugeApiImpl extends GaugeApi {

  def increment(name: String): Unit = {
    initializedGaugeMetric(name).withoutTags().increment()
  }

  def increment(name: String, duration: Long): Unit = {
    initializedGaugeMetric(name).withoutTags().increment(duration)
  }

  def incrementWithTags(name: String, tags: Map[String, String] = Map.empty): Unit = {
    initializedGaugeMetric(name).withTags(TagSet.from(tags)).increment()
  }

  def updateWithTags(name: String, value: Long, tags: Map[String, String] = Map.empty): Unit = {
    initializedGaugeMetric(name).withTags(TagSet.from(tags)).update(value)
  }

  def update(name: String, value: Long): Unit = {
    initializedGaugeMetric(name).withoutTags().update(value)
  }

  private def gaugeMetric(name: String): Metric.Gauge = Kamon.gauge(name)

  private def initializedGaugeMetric(name: String): Metric.Gauge = {
    val gm = gaugeMetric(name)
    gm
  }
}

trait HistogramApi {
  def record(name: String, unit: MeasurementUnit, value: Long): Unit
  def recordWithTag(name: String, unit: MeasurementUnit, value: Long, tag: (String, String)): Unit
  def recordWithTags(name: String, unit: MeasurementUnit, value: Long, tags: Map[String, String] = Map.empty): Unit
}

object HistogramApiImpl extends HistogramApi {
  override def record(name: String, unit: MeasurementUnit, value: Long): Unit =
    initializedHistogramMetric(name).withoutTags().record(value)

  def recordWithTag(name: String, unit: MeasurementUnit, value: Long, tag: (String, String)): Unit = {
    initializedHistogramMetric(name)
      .withTag(tag._1, tag._2)
      .record(value)
  }

  def recordWithTags(name: String, unit: MeasurementUnit, value: Long, tags: Map[String, String] = Map.empty): Unit = {
    initializedHistogramMetric(name).withTags(TagSet.from(tags)).record(value)
  }

  private def histogramMetric(name: String): Metric.Histogram = Kamon.histogram(name)

  private def initializedHistogramMetric(name: String): Metric.Histogram = {
    val hist = histogramMetric(name)
    hist
  }
}
