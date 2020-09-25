package com.evernym.verity.metrics

import kamon.Kamon
import kamon.metric.Metric
import kamon.tag.TagSet

/**
 * metrics api to add/update metrics values
 */
object MetricsWriter {

  var initialized: Set[String] = Set.empty

  lazy val gaugeApi: GaugeApi = GaugeApiImpl

  def addToInitialized(name: String): Unit = {
    if (! initialized.contains(name)) {
      initialized = initialized + name
    }
  }

  def isInitialized(name: String): Boolean = initialized.contains(name)
}

trait GaugeApi {
  def increment(name: String): Unit
  def increment(name: String, duration: Long): Unit
  def incrementWithTags(name: String, tags: Map[String, String] = Map.empty)
  def updateWithTags(name: String, value: Long, tags: Map[String, String] = Map.empty)
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

  private def gaugeMetric(name: String): Metric.Gauge = Kamon.gauge(name)

  private def initializedGaugeMetric(name: String): Metric.Gauge = {
    val gm = gaugeMetric(name)
    if (! MetricsWriter.isInitialized(name)) {
      gm.withoutTags().update(0)
      MetricsWriter.addToInitialized(name)
    }
    gm
  }
}
