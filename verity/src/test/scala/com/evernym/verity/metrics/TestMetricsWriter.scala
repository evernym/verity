package com.evernym.verity.metrics

import java.time.Instant
import scala.collection.mutable

class TestMetricsWriter extends MetricsWriter {

  val gaugesMap = new mutable.HashMap[TestMetricHead, Double]()
  val histogramsMap = new mutable.HashMap[TestMetricHead, HistogramEntry]

  override def gaugeIncrement(name: String, value: Double, tags: TagMap): Unit = this.synchronized {
    val key = TestMetricHead(name, tags)
    val cur = gaugesMap.getOrElse(key, 0.0)
    gaugesMap.put(key, cur + value)
  }

  override def gaugeDecrement(name: String, value: Double, tags: TagMap): Unit = this.synchronized {
    val key = TestMetricHead(name, tags)
    val cur = gaugesMap.getOrElse(key, 0.0)
    gaugesMap.put(key, cur - value)
  }

  override def gaugeUpdate(name: String, value: Double, tags: TagMap): Unit = this.synchronized {
    val key = TestMetricHead(name, tags)
    gaugesMap.put(key, value)
  }

  override def histogramUpdate(name: String, unit: MetricsUnit, value: Long, tags: TagMap): Unit = this.synchronized {
    val key = TestMetricHead(name, tags)
    val cur = histogramsMap.getOrElse(key, HistogramEntry())
    cur.count += 1
    cur.sum += value
    histogramsMap.put(key, cur)
  }

  override def taggedSpan(name: String, start: Instant, tags: TagMap): Unit = ()

  override def runWithSpan[T](opName: String, componentName: String, spanType: SpanType)(fn: => T): T = fn

  override def setup(): Unit = ()

  override def shutdown(): Unit = ()

  def filterGaugeMetrics(prefix: String): Map[TestMetricHead, Double] = this.synchronized {
    gaugesMap.filter(_._1.name.startsWith(prefix)).toMap
  }

  def filterHistogramMetrics(prefix: String): Map[TestMetricHead, HistogramEntry] = this.synchronized {
    histogramsMap.filter(_._1.name.startsWith(prefix)).toMap
  }

  def allGaugeMetrics(): Map[TestMetricHead, Double] = this.synchronized {
    gaugesMap.toMap
  }

  def allHistogramMetrics(): Map[TestMetricHead, HistogramEntry] = this.synchronized {
    histogramsMap.toMap
  }

  def reset(): Unit = this.synchronized {
    gaugesMap.clear()
    histogramsMap.clear()
  }
}

case class TestMetricHead(name: String, tags: Map[String, String]) {
  override def equals(o: Any): Boolean = {
    o match {
      case TestMetricHead(otherName, otherTags) => otherName == name && tags.toSet == otherTags.toSet
      case _ => false
    }
  }
}

case class HistogramEntry(var count: Long = 0, var sum: Double = 0.0)
