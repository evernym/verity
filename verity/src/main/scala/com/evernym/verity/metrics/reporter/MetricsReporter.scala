package com.evernym.verity.metrics.reporter

/**
 * metrics reporter interface to be implemented by any metrics reporting implementation
 */
trait MetricsReporter {
  def getFixedMetrics: List[MetricDetail]
  def getResetMetrics: List[MetricDetail]
  def resetMetrics(): Unit
}

case class MetricDetail(name: String, target: String, value: Double, tags: Option[Map[String, String]])