package com.evernym.verity.metrics.reporter

import com.evernym.verity.metrics.MetricsReader

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
                        tags: Option[Map[String, String]]) {

  def isName(givenName: String): Boolean = {
    name == MetricsReader.convertToProviderName(givenName)
  }
  def isNameStartsWith(givenName: String): Boolean = {
    name.startsWith(MetricsReader.convertToProviderName(givenName))
  }
}
