package com.evernym.verity.metrics

import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.typesafe.config.Config

import scala.util.matching.Regex

case class MetricsFilter(config: Config) {

  def isExcluded(name: String): Boolean = {
    excludeFilterHelper.filterRegEx.exists(_.pattern.matcher(name).matches())
  }

  private val excludeFilterHelper = FilterHelper(config, "verity.metrics.writer.exclude")
}

case class FilterHelper(config: Config, path: String) {

  val filterRegEx: Set[Regex] = getFilterConfig.map(_.r)
  private def getFilterConfig: Set[String] = ConfigReadHelper(config).getStringSetOption(path).getOrElse(Set.empty)
}