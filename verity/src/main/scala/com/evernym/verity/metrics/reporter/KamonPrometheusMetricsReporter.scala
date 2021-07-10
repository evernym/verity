package com.evernym.verity.metrics.reporter

import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger
import kamon.Kamon
import kamon.module.Module.Registration
import kamon.prometheus.PrometheusReporter


//NOTE: whenever metric code changes, update doc as well if needed
// https://docs.google.com/document/d/1SAZNg8pMse9tIEfYE00PiLaJTUgC6c1uVBsLMfj-qxA/edit#

object KamonPrometheusMetricsReporter extends MetricsReporter {

  val logger: Logger = getLoggerByClass(getClass)

  /**
   * this reporter keeps tracking metrics from the time verity starts
   */
  val FIXED_REPORTER_NAME = "fixed-reporter"
  private var fixedReporter = createFixedMetricsReporter()

  private def fixedMetricsData: String = fixedReporter.reporter.scrapeData()

  def createFixedMetricsReporter(): RegisteredReporter = {
    Option(fixedReporter).foreach(_.registration.cancel())
    val reporter = new PrometheusReporter()
    val registration = Kamon.registerModule(FIXED_REPORTER_NAME, reporter)
    RegisteredReporter(FIXED_REPORTER_NAME, reporter, registration)
  }

  override def fixedMetrics: List[MetricDetail] =
    buildMetrics(fixedMetricsData)

  private def getCleanedInputStr(metricLine: String): String =
    metricLine.stripMargin.replaceAll("\n", " ")

  def buildMetrics(inputStr: String): List[MetricDetail] = {
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
        Option(head.substring(head.indexOf("{")+1, head.length-1))
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
      .map( token => token.split("=", 2))
      .map( splitted =>
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

  //only exists for tests purposes
  // (may be we should find better way to handle a need to reset metrics reporter before each test)
  def _resetFixedMetricsReporter(): Unit = {
    fixedReporter = createFixedMetricsReporter()
  }
}

trait TargetBuilder {
  import com.evernym.verity.metrics.reporter.KamonPrometheusMetricsReporter.targetConnector

  def getConfiguredTagsByTargetType(typ: String): Option[Set[String]] = AppConfigWrapper.getStringSetOption(typ)

  def getTagsKeyByType(typ: String, defaultKeys: Set[String]): Set[String] =
    getConfiguredTagsByTargetType(typ).getOrElse(defaultKeys).filter(_.nonEmpty)

  def defaultKeys: Set[String] = Set.empty

  def buildTarget(tags:Map[String, String]):String  = {
    val targetKeySetByType: Set[String] = getTagsKeyByType(METRICS_TARGET_AKKA_SYSTEM, defaultKeys)
    val tagKeySet: Set[String] = tags.keySet
    val targetKeys = targetKeySetByType.intersect(tagKeySet)

    targetKeys.map { target =>
      tags.get(target)
    }.filter(_.isDefined).map(_.get).mkString(targetConnector)
  }
}

object AkkaSystemTargetBuilder extends TargetBuilder {
  override def buildTarget(tags:Map[String, String]):String  = "actor_system"
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

  lazy val targetBuilders: Map[String, TargetBuilder] = Map (
    "akka_system"       -> AkkaSystemTargetBuilder,
    "akka_group"        -> AkkaGroupTargetBuilder,
    "akka_actor"        -> AkkaActorTargetBuilder,
    "executor_pool"     -> ExecutorPoolTargetBuilder,
    "executor_tasks"    -> ExecutorTaskTargetBuilder,
    "executor_queue"    -> ExecutorQueueTargetBuilder,
    "executor_threads"  -> ExecutorThreadsTargetBuilder
  )

  def getByType(typ: String): TargetBuilder = targetBuilders(typ)
}

case class ParsedMetricParam(name: String, value: Double, tags: Map[String, String])

case class RegisteredReporter(name: String, reporter: PrometheusReporter, registration: Registration)