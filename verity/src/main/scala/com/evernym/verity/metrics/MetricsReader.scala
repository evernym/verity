package com.evernym.verity.metrics

import com.evernym.verity.Exceptions.FeatureNotEnabledException
import com.evernym.verity.Status._
import com.evernym.verity.actor.{ActorMessage, MetricsFilterCriteria}
import com.evernym.verity.config.{AppConfig, AppConfigWrapper}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.metrics.reporter.{KamonPrometheusMetricsReporter, MetricDetail, MetricsReporter}
import kamon.Kamon
import org.joda.time.{DateTime, DateTimeZone}

/**
 * metrics reporter api to get or reset metrics
 */
object MetricsReader {

  private val lastResetTimestamp: String = getCurrentTimestamp
  private val hostName: String = AppConfigWrapper.getConfigStringReq(KAMON_ENV_HOST)

  private var metricsReporter: Option[MetricsReporter] = None

  def initialize(appConfig: AppConfig): Unit = {
    if (appConfig.getConfigStringOption(METRICS_ENABLED).forall(_ == YES)) {
      Kamon.loadModules()   //for system/jvm related metrics
      metricsReporter = Some(KamonPrometheusMetricsReporter)
    }
  }

  private def getCurrentTimestamp: String = new DateTime(new DateTime()).withZone(DateTimeZone.UTC).toString()

  private def buildMetadata: MetaData = MetaData(hostName, getCurrentTimestamp, lastResetTimestamp)

  def getNodeMetrics(criteria: MetricsFilterCriteria = MetricsFilterCriteria()): NodeMetricsData = {
    metricsReporter.map { mr =>
      val metadata = if (criteria.includeMetaData) Some(buildMetadata) else None
      val fixedMetrics = mr.fixedMetrics
      val allFilteredMetrics =
        if (criteria.filtered) MetricsFilter.filterMetrics(fixedMetrics)
        else fixedMetrics
      val allFinalMetrics = allFilteredMetrics.map { m =>
        if (criteria.includeTags) m
        else m.copy(tags = None)
      }
      NodeMetricsData(metadata, allFinalMetrics)
    }.getOrElse{
      throw new FeatureNotEnabledException(METRICS_CAPTURING_STATUS_NOT_ENABLED.statusCode,
        Option(METRICS_CAPTURING_STATUS_NOT_ENABLED.statusMsg))
    }
  }

  def convertToProviderName(name: String): String = {
    name
      .replace(".", "_")
      .replace("-", "_")
  }
}

case class MetaData(nodeName: String, timestamp: String, lastResetTimestamp: String)
case class NodeMetricsData(metadata: Option[MetaData], metrics: List[MetricDetail]) extends ActorMessage
case class AllNodeMetricsData (data: List[NodeMetricsData]) extends ActorMessage
