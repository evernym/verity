package com.evernym.verity.metrics

import java.time.Instant
import scala.collection.mutable

// todo This is non-finished design for test metrics writer
class TestMetricsWriter extends MetricsWriter {

  // todo use concurrent collections
  val gaugesMap = new mutable.HashMap[TestMetricHead, Double]().withDefault(_ => 0)
  val histogramsMap = new mutable.HashMap[TestMetricHead, mutable.MutableList[Long]].withDefault(_ => new mutable.MutableList[Long])

  //todo this is temporary implementations for testing!
  override def gaugeIncrement(name: String, value: Double, tags: TagMap): Unit = this.synchronized {
    val key = TestMetricHead(name, tags)
    val tmp = gaugesMap(key)
    gaugesMap(key) += value
  }

  override def gaugeUpdate(name: String, value: Double, tags: TagMap): Unit = this.synchronized {
    val key = TestMetricHead(name, tags)
    gaugesMap(key) = value
  }

  override def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap): Unit = this.synchronized {
    val key = TestMetricHead(name, tags)
    histogramsMap(key) += value
  }


  override def taggedSpan(name: String, start: Instant, tags: TagMap): Unit = ()

  override def runWithSpan[T](opName: String, componentName: String, spanType: SpanType)(fn: => T): T = fn

  override def setup(): Unit = ()

  override def shutdown(): Unit = ()

  //todo metrics reader
  def filterGaugeMetrics(prefix: String): Map[TestMetricHead, Double] = this.synchronized {
    gaugesMap.filter(_._1.name.startsWith(prefix)).toMap
  }

  def filterHistogramMetrics(prefix: String): Map[TestMetricHead, mutable.MutableList[Long]] = this.synchronized {
    histogramsMap.filter(_._1.name.startsWith(prefix)).toMap
  }

  def allGaugeMetrics(): Map[TestMetricHead, Double] = this.synchronized {
    gaugesMap.toMap
  }

  def allHistogramMetrics(): Map[TestMetricHead, mutable.MutableList[Long]] = this.synchronized {
    histogramsMap.toMap
  }

  def reset(): Unit = this.synchronized {
    gaugesMap.clear()
    histogramsMap.clear()
  }
}

case class TestMetricHead(val name: String, val tags: Map[String, String]) {
  override def equals(o: Any): Boolean = {
    o match {
      case TestMetricHead(otherName, otherTags) => {
        // todo rework!
        otherName == name && tags.map { case (k, v) => k + ":" + v }.mkString("||") == otherTags.map { case (k, v) => k + ":" + v }.mkString("||")
      }
      case _ => false
    }

  }
}

