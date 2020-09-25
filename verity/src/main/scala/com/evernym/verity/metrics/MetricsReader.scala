package com.evernym.verity.metrics

import com.evernym.verity.Exceptions.FeatureNotEnabledException
import com.evernym.verity.Status._
import com.evernym.verity.actor.{ActorMessageClass, MetricsFilterCriteria}
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.metrics.reporter.{KamonPrometheusMetricsReporter, MetricDetail, MetricsReporter}
import com.typesafe.scalalogging.Logger
import kamon.Kamon
import org.joda.time.{DateTime, DateTimeZone}

/**
 * metrics reporter api to get or reset metrics
 */
object MetricsReader {

  val logger: Logger = getLoggerByName("MetricsReader")
  var explicitlyReset: Boolean = false
  var lastResetTimestamp: String = getCurrentTimestamp
  val hostName: String = AppConfigWrapper.getConfigStringReq(KAMON_ENV_HOST)

  Kamon.loadModules()   //for system/jvm related metrics

  val metricsReporter: Option[MetricsReporter] = {
    if (AppConfigWrapper.getConfigStringOption(METRICS_ENABLED).forall(_ == YES))
      Some(KamonPrometheusMetricsReporter)
    else None
  }

  def getCurrentTimestamp: String = new DateTime(new DateTime()).withZone(DateTimeZone.UTC).toString()

  def buildMetadata: MetaData = MetaData(hostName, getCurrentTimestamp, lastResetTimestamp)

  def resetNodeMetrics(): Unit = {
    metricsReporter match {
      case Some(mp) =>
        logger.debug("resetting metrics...")
        lastResetTimestamp = getCurrentTimestamp
        explicitlyReset = true
        mp.resetMetrics()
        logger.debug("resetting metrics done.")
      case None => throw new FeatureNotEnabledException(METRICS_CAPTURING_STATUS_NOT_ENABLED.statusCode,
        Option(METRICS_CAPTURING_STATUS_NOT_ENABLED.statusMsg))
    }
  }

  def getNodeMetrics(criteria: MetricsFilterCriteria): NodeMetricsData = {
    metricsReporter.map { mp =>
      val metadata = if (criteria.includeMetaData) Some(buildMetadata) else None
      val fixedMetrics = mp.getFixedMetrics
      val resetMetrics = if (criteria.includeReset && explicitlyReset) mp.getResetMetrics else List.empty
      val allMetrics = fixedMetrics ++ resetMetrics
      val allFilteredMetrics = if (criteria.filtered) MetricsFilter.filterMetrics(allMetrics) else allMetrics
      val allFinalMetrics = allFilteredMetrics.map { m =>
        if (criteria.includeTags) m
        else m.copy(tags = None)
      }
      NodeMetricsData(metadata, allFinalMetrics)
    }.getOrElse(throw new FeatureNotEnabledException(METRICS_CAPTURING_STATUS_NOT_ENABLED.statusCode,
      Option(METRICS_CAPTURING_STATUS_NOT_ENABLED.statusMsg)))
  }

  def convertToProviderName(name: String): String = {
    name
      .replace(".", "_")
      .replace("-", "_")
  }
}

case class MetaData(nodeName: String, timestamp: String, lastResetTimestamp: String)
case class NodeMetricsData(metadata: Option[MetaData], metrics: List[MetricDetail]) extends ActorMessageClass
case class AllNodeMetricsData (data: List[NodeMetricsData]) extends ActorMessageClass
