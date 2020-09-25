package com.evernym.verity.metrics

import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.metrics.reporter.MetricDetail
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config.{Config, ConfigUtil}
import com.typesafe.scalalogging.Logger
import kamon.util.Filter

import scala.collection.JavaConverters._


trait MetricDetailMatcher {
  def accept(md: MetricDetail): Boolean
}

case class FilterMatcher(name: Option[Filter], value: Option[Filter], target: Option[Filter],
                         tags: Option[Map[String, Filter]]) {
  def accept(md: MetricDetail): Boolean = {

    val applicableMatchersCount = Set(name, value, target, tags).count(_.isDefined)

    if (applicableMatchersCount == 0) {
      false
    } else {
      val mdTags = md.tags.getOrElse(Map.empty)

      name.forall(n => n.accept(md.name)) &&
        target.forall(t => t.accept(md.target)) &&
          tags.getOrElse(Map.empty).forall( tag => mdTags.get(tag._1).exists(tag._2.accept)) &&
            value.forall(n => n.accept(md.value.toString))
    }
  }

}

case class FilterMatchers(matchers: Seq[FilterMatcher]=Seq.empty) extends MetricDetailMatcher {
  override def accept(md: MetricDetail): Boolean =
    matchers.exists(_.accept(md))
}

class Filters(filters: Map[String, MetricDetailMatcher]) {
  def accept(filterName: String, md: MetricDetail): Boolean =
    filters
      .get(filterName)
        .exists(fms => fms.accept(md))

  def get(filterName: String): MetricDetailMatcher = {
    filters.getOrElse(filterName, new MetricDetailMatcher {
      override def accept(md: MetricDetail): Boolean = false
    })
  }
}

class IncludeExcludeMatcher(includes: FilterMatchers, excludes: FilterMatchers) extends MetricDetailMatcher {
  override def accept(md: MetricDetail): Boolean =
    includes.matchers.exists(_.accept(md)) && !excludes.matchers.exists(_.accept(md))
}

trait MetricsFilter {

  val logger: Logger = getLoggerByName("MetricsFilter")

  lazy val metricsFilterConfigPath: String = METRICS_UTIL_FILTERS
  lazy val applicableFilterNames: List[String] = List("general")

  def metricsConfig: Config

  def filters: Filters = fromConfig(metricsConfig)

  def applicableFilters: List[MetricDetailMatcher] = applicableFilterNames.map(filters.get)

  def filterMetrics(allMetrics: List[MetricDetail]): List[MetricDetail] = {

    logger.debug("metrics size before filter: "  + allMetrics.size)
    val filteredMetrics: List[MetricDetail] = try {
      AppConfigWrapper.getLoadedConfig.getConfig(metricsFilterConfigPath)
      allMetrics.filter { m =>
        applicableFilters.forall(_.accept(m))
      }
    } catch {
      case _: Missing =>
        logger.debug(s"filter configuration not found at this path: $metricsFilterConfigPath, metrics will be returned without any filtering")
        allMetrics
    }
    logger.debug("metrics size after filter: "  + filteredMetrics.size)
    filteredMetrics
  }

  def fromConfig(config: Config): Filters = {
    val filtersConfig = config.getConfig(metricsFilterConfigPath)
    val matchers = applicableFilterNames.filter { fn =>
      filtersConfig.hasPath(fn)
    }.map { fn =>
      val includes = readFilters(filtersConfig, fn, "includes")
      val excludes = readFilters(filtersConfig, fn, "excludes")
      (fn, new IncludeExcludeMatcher(FilterMatchers(includes), FilterMatchers(excludes)))
    }.toMap

    new Filters(matchers)
  }

  private def getMapFromConfigObject(fc: Config): Map[String, Filter] = {
    val tags = fc.getObject("tags")
    val entry = tags.entrySet().asScala
    entry.map { e =>
      e.getKey -> Filter.fromGlob(e.getValue.unwrapped().toString)
    }.toMap
  }

  def readFilters(filtersConfig: Config, name: String, key: String): Seq[FilterMatcher] = {

    val configKey = ConfigUtil.joinPath(name, key)

    if (filtersConfig.hasPath(configKey)) {
      filtersConfig.getConfigList(configKey).asScala.map { fc =>
        val nameMatcher = if (fc.hasPath("name")) Some(Filter.fromGlob(fc.getString("name"))) else None
        val valueMatcher = if (fc.hasPath("value")) Some(Filter.fromGlob(fc.getDouble("value").toString)) else None
        val targetMatcher = if (fc.hasPath("target")) Some(Filter.fromGlob(fc.getString("target"))) else None
        val tagsMatcher = if (fc.hasPath("tags")) Some(getMapFromConfigObject(fc)) else None
        FilterMatcher(nameMatcher, valueMatcher, targetMatcher, tagsMatcher)
      }
    } else {
      Seq.empty
    }
  }

}

object MetricsFilter extends MetricsFilter{
  override def metricsConfig: Config = AppConfigWrapper.getLoadedConfig
}