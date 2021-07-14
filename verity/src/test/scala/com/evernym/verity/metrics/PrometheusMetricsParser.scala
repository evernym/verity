package com.evernym.verity.metrics

import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.MetricDetail.convertToProviderName
import com.evernym.verity.metrics.PrometheusMetricsParser.targetConnector
import com.typesafe.scalalogging.Logger

object PrometheusMetricsParser {

  val logger: Logger = getLoggerByClass(getClass)

  def parseString(inputStr: String): List[MetricDetail] = {
    try {
      inputStr.split("\n").
        filter(l => l.nonEmpty).
        filterNot(_.startsWith("#")).
        toList.flatMap { metricLine =>
        buildMetric(metricLine)
      }
    } catch {
      case _: Throwable =>
        logger.error("error while parsing metrics from input: " + inputStr)
        List.empty
    }
  }

  private def getCleanedInputStr(metricLine: String): String =
    metricLine.stripMargin.replaceAll("\n", " ")

  def buildMetric(metricLine: String): Option[MetricDetail] = {
    try {
      val cleanedInputStr = getCleanedInputStr(metricLine)
      val parsedMetricParam = parse(cleanedInputStr)
      val name = parsedMetricParam.name
      val value = parsedMetricParam.value
      val target = buildTarget(parsedMetricParam)
      val tags = Option(parsedMetricParam.tags)
      Option(MetricDetail(name, target, value, tags))
    } catch {
      case _: Throwable =>
        logger.error("error while parsing metrics from input: " + metricLine)
        None
    }
  }

  private def parse(inputStr: String): ParsedMetricParam = {
    val (head, value) = (
      inputStr.substring(0, inputStr.lastIndexOf(" ")),
      inputStr.substring(inputStr.lastIndexOf(" ")))
    val (name, tagString) = try {
      (
        head.substring(0, head.indexOf("{")),
        Option(head.substring(head.indexOf("{") + 1, head.length - 1))
      )
    } catch {
      case _: StringIndexOutOfBoundsException =>
        (head, None)
    }
    ParsedMetricParam(name, value.trim.toDouble, tagString.map(parseTags).getOrElse(Map.empty))
  }

  private def parseTags(tagString: String): Map[String, String] = {
    val result = tagString
      .trim
      .split("\",")
      .map(token => token.split("=", 2))
      .map(splitted =>
        splitted.head.trim -> splitted.tail.lastOption.getOrElse("n/a").trim.replace("\"", ""))
      .toMap
    result
  }

  private def buildTarget(parsedMetricParam: ParsedMetricParam): String = {
    val name = parsedMetricParam.name
    TargetBuilder.targetBuilders.find(tb => name.startsWith(tb._1) || name.contains(tb._1)).map { case (_, tb) =>
      tb.buildTarget(parsedMetricParam.tags)
    }.getOrElse(DEFAULT_TARGET)
  }

  val DEFAULT_TARGET = "unknown"
  lazy val targetConnector: String = AppConfigWrapper.getStringOption(METRICS_TARGET_CONNECTOR).getOrElse("-")
}

trait TargetBuilder {

  def getConfiguredTagsByTargetType(typ: String): Option[Set[String]] = AppConfigWrapper.getStringSetOption(typ)

  def getTagsKeyByType(typ: String, defaultKeys: Set[String]): Set[String] =
    getConfiguredTagsByTargetType(typ).getOrElse(defaultKeys).filter(_.nonEmpty)

  def defaultKeys: Set[String] = Set.empty

  def buildTarget(tags: Map[String, String]): String = {
    val targetKeySetByType: Set[String] = getTagsKeyByType(METRICS_TARGET_AKKA_SYSTEM, defaultKeys)
    val tagKeySet: Set[String] = tags.keySet
    val targetKeys = targetKeySetByType.intersect(tagKeySet)

    targetKeys.map { target =>
      tags.get(target)
    }.filter(_.isDefined).map(_.get).mkString(targetConnector)
  }
}

object AkkaSystemTargetBuilder extends TargetBuilder {
  override def buildTarget(tags: Map[String, String]): String = "actor_system"
}

object AkkaGroupTargetBuilder extends TargetBuilder {
  override def defaultKeys = Set("group")
}

object AkkaActorTargetBuilder extends TargetBuilder {
  override def defaultKeys = Set("path", "type", "id")
}

object ExecutorPoolTargetBuilder extends TargetBuilder {
  override def defaultKeys = Set("name", "type", "setting")
}

object ExecutorTaskTargetBuilder extends TargetBuilder {
  override def defaultKeys = Set("name", "type", "state")
}

object ExecutorQueueTargetBuilder extends TargetBuilder {
  override def defaultKeys = Set("name", "type")
}

object ExecutorThreadsTargetBuilder extends TargetBuilder {
  override def defaultKeys = Set("name", "type", "state")
}

object TargetBuilder extends TargetBuilder {

  lazy val targetBuilders: Map[String, TargetBuilder] = Map(
    "akka_system" -> AkkaSystemTargetBuilder,
    "akka_group" -> AkkaGroupTargetBuilder,
    "akka_actor" -> AkkaActorTargetBuilder,
    "executor_pool" -> ExecutorPoolTargetBuilder,
    "executor_tasks" -> ExecutorTaskTargetBuilder,
    "executor_queue" -> ExecutorQueueTargetBuilder,
    "executor_threads" -> ExecutorThreadsTargetBuilder
  )

  def getByType(typ: String): TargetBuilder = targetBuilders(typ)
}

case class ParsedMetricParam(name: String, value: Double, tags: Map[String, String])

case class MetricDetail(name: String, target: String, value: Double, tags: Option[Map[String, String]]) {
  def isName(givenName: String): Boolean = {
    name == convertToProviderName(givenName)
  }

  def isNameStartsWith(givenName: String): Boolean = {
    name.startsWith(convertToProviderName(givenName))
  }

}

object MetricDetail {
  def convertToProviderName(name: String): String = {
    name
      .replace(".", "_")
      .replace("-", "_")
  }

}

