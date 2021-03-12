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
  def increment(name: String, times: Double): Unit
  def decrement(name: String): Unit
  def decrement(name: String, times: Double): Unit
  def incrementWithTags(name: String, tags: Map[String, String] = Map.empty)
  def incrementWithTags(name: String, value: Double, tags: Map[String, String])
  def decrementWithTags(name: String, tags: Map[String, String] = Map.empty)
  def decrementWithTags(name: String, value: Double, tags: Map[String, String])
  def updateWithTags(name: String, value: Double, tags: Map[String, String] = Map.empty)
  def update(name: String, value: Double): Unit
}


object GaugeApiImpl extends GaugeApi {

  def increment(name: String): Unit = {
    initializedGaugeMetric(name).withoutTags().increment()
  }

  def increment(name: String, times: Double): Unit = {
    initializedGaugeMetric(name).withoutTags().increment(times)
  }

  def decrement(name: String): Unit = {
    initializedGaugeMetric(name).withoutTags().decrement()
  }

  def decrement(name: String, times: Double): Unit = {
    initializedGaugeMetric(name).withoutTags().decrement(times)
  }

  def incrementWithTags(name: String, tags: Map[String, String] = Map.empty): Unit = {
    initializedGaugeMetric(name).withTags(TagSet.from(tags)).increment()
  }

  def incrementWithTags(name: String, value: Double, tags: Map[String, String]): Unit = {
    initializedGaugeMetric(name).withTags(TagSet.from(tags)).increment(value)
  }

  def decrementWithTags(name: String, tags: Map[String, String] = Map.empty): Unit = {
    initializedGaugeMetric(name).withTags(TagSet.from(tags)).decrement()
  }

  def decrementWithTags(name: String, value: Double, tags: Map[String, String]): Unit = {
    initializedGaugeMetric(name).withTags(TagSet.from(tags)).decrement(value)
  }


  def updateWithTags(name: String, value: Double, tags: Map[String, String] = Map.empty): Unit = {
    initializedGaugeMetric(name).withTags(TagSet.from(tags)).update(value)
  }

  def update(name: String, value: Double): Unit = {
    initializedGaugeMetric(name).withoutTags().update(value)
  }

  private def gaugeMetric(name: String): Metric.Gauge = Kamon.gauge(name)

  private def initializedGaugeMetric(name: String): Metric.Gauge = {
    gaugeMetric(name)
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
    histogramMetric(name)
  }
}
