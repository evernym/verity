package com.evernym.verity.metrics

import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.MetricDetail.convertToProviderName
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.typesafe.scalalogging.Logger

object PrometheusMetricsParser {

  val logger: Logger = getLoggerByClass(getClass)

  def parseString(inputStr: String, ac: AppConfig): List[MetricDetail] = {
    try {
      inputStr.split("\n").
        filter(l => l.nonEmpty).
        filterNot(_.startsWith("#")).
        toList.flatMap { metricLine =>
        buildMetric(metricLine, ac)
      }
    } catch {
      case _: Throwable =>
        logger.error("error while parsing metrics from input: " + inputStr)
        List.empty
    }
  }

  private def getCleanedInputStr(metricLine: String): String =
    metricLine.stripMargin.replaceAll("\n", " ")

  def buildMetric(metricLine: String, ac: AppConfig): Option[MetricDetail] = {
    try {
      val cleanedInputStr = getCleanedInputStr(metricLine)
      val parsedMetricParam = parse(cleanedInputStr)
      val name = parsedMetricParam.name
      val value = parsedMetricParam.value
      val target = buildTarget(parsedMetricParam, ac)
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

  private def buildTarget(parsedMetricParam: ParsedMetricParam, ac: AppConfig): String = {
    val name = parsedMetricParam.name
    new TargetBuilder(ac).targetBuilders.find(tb => name.startsWith(tb._1) || name.contains(tb._1)).map { case (_, tb) =>
      tb.buildTarget(parsedMetricParam.tags)
    }.getOrElse(DEFAULT_TARGET)
  }

  val DEFAULT_TARGET = "unknown"
}

trait ITargetBuilder extends HasAppConfig {

  def getConfiguredTagsByTargetType(typ: String): Option[Set[String]] = appConfig.getStringSetOption(typ)
  lazy val targetConnector: String = appConfig.getStringOption(METRICS_TARGET_CONNECTOR).getOrElse("-")

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

class AkkaSystemTargetBuilder(val ac: AppConfig) extends ITargetBuilder {
  override def buildTarget(tags: Map[String, String]): String = "actor_system"

  override def appConfig: AppConfig = ac
}

class AkkaGroupTargetBuilder(val ac: AppConfig) extends ITargetBuilder {
  override def defaultKeys = Set("group")

  override def appConfig: AppConfig = ac
}

class AkkaActorTargetBuilder(val ac: AppConfig) extends ITargetBuilder {
  override def defaultKeys = Set("path", "type", "id")

  override def appConfig: AppConfig = ac
}

class ExecutorPoolTargetBuilder(val ac: AppConfig) extends ITargetBuilder {
  override def defaultKeys = Set("name", "type", "setting")

  override def appConfig: AppConfig = ac
}

class ExecutorTaskTargetBuilder(val ac: AppConfig) extends ITargetBuilder {
  override def defaultKeys = Set("name", "type", "state")

  override def appConfig: AppConfig = ac
}

class ExecutorQueueTargetBuilder(val ac: AppConfig) extends ITargetBuilder {
  override def defaultKeys = Set("name", "type")

  override def appConfig: AppConfig = ac
}

class ExecutorThreadsTargetBuilder(val ac: AppConfig) extends ITargetBuilder {
  override def defaultKeys = Set("name", "type", "state")

  override def appConfig: AppConfig = ac
}

class TargetBuilder(val ac: AppConfig) extends ITargetBuilder {

  lazy val targetBuilders: Map[String, ITargetBuilder] = Map(
    "akka_system" -> new AkkaSystemTargetBuilder(ac),
    "akka_group" -> new AkkaGroupTargetBuilder(ac),
    "akka_actor" -> new AkkaActorTargetBuilder(ac),
    "executor_pool" -> new ExecutorPoolTargetBuilder(ac),
    "executor_tasks" -> new ExecutorTaskTargetBuilder(ac),
    "executor_queue" -> new ExecutorQueueTargetBuilder(ac),
    "executor_threads" -> new ExecutorThreadsTargetBuilder(ac)
  )

  def getByType(typ: String): ITargetBuilder = targetBuilders(typ)

  override def appConfig: AppConfig = ac
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

