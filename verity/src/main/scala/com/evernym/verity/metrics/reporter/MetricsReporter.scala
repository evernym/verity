package com.evernym.verity.metrics.reporter

/**
 * metrics reporter interface to be implemented by any metrics reporting implementation
 */
trait MetricsReporter {
  /**
   * provides metrics recorded since the verity instance startup
   * @return
   */
  def fixedMetrics: List[MetricDetail]

  /**
   * provides metrics recorded since last metrics reset
   * @return
   */
  def postResetMetrics: List[MetricDetail]

  /**
   * reset the metrics
   */
  def resetMetrics(): Unit
}

case class MetricDetail(name: String,
                        target: String,
                        value: Double,
                        tags: Option[Map[String, String]])