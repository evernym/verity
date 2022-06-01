package com.evernym.verity.http.common

import com.evernym.verity.observability.metrics.CustomMetrics.{AS_ENDPOINT_HTTP_AGENT_MSG_COUNT, AS_ENDPOINT_HTTP_AGENT_MSG_FAILED_COUNT, AS_ENDPOINT_HTTP_AGENT_MSG_SUCCEED_COUNT}
import com.evernym.verity.observability.metrics.MetricsWriter

trait MetricsSupport {

  def metricsWriter: MetricsWriter

  def incrementAgentMsgCount(): Unit = metricsWriter.gaugeIncrement(AS_ENDPOINT_HTTP_AGENT_MSG_COUNT)

  def incrementAgentMsgSucceedCount(): Unit = metricsWriter.gaugeIncrement(AS_ENDPOINT_HTTP_AGENT_MSG_SUCCEED_COUNT)

  def incrementAgentMsgFailedCount(tags: Map[String, String] = Map.empty): Unit =
    metricsWriter.gaugeIncrement(AS_ENDPOINT_HTTP_AGENT_MSG_FAILED_COUNT, tags = tags)
}
