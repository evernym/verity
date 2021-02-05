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
}

case class MetricDetail(name: String,
                        target: String,
                        value: Double,
                        tags: Option[Map[String, String]])