package com.evernym.verity.metrics.reporter

import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.metrics.reporter.KamonPrometheusMetricsReporter.targetConnector
import kamon.Kamon
import kamon.prometheus.PrometheusReporter


//NOTE: whenever metric code changes, update doc as well if needed
// https://docs.google.com/document/d/1SAZNg8pMse9tIEfYE00PiLaJTUgC6c1uVBsLMfj-qxA/edit#

object KamonPrometheusMetricsReporter extends MetricsReporter {


  val DEFAULT_TARGET = "unknown"
  val FIXED_REPORTER_NAME = "fixed-reporter"
  val RESET_BASED_REPORTER_NAME = "reset-based-reporter"

  /**
   * this reporter keeps tracking metrics from the time agency starts
   */
  private val fixedReporter = createAndAddMetricsReporter(FIXED_REPORTER_NAME)

  /**
   * this reporter may get reset if corresponding internal api gets called
   * and it will only tracks metrics post that reset
   */
  private var resetBasedReporter = createAndAddMetricsReporter(RESET_BASED_REPORTER_NAME)

  private def fixedMetricsData: String = fixedReporter.scrapeData()
  private def resetBasedMetricsData: String = resetBasedReporter.scrapeData()

  lazy val resetMetricsNameSuffix: String =
    AppConfigWrapper.getConfigStringOption(RESET_METRICS_NAME_SUFFIX).getOrElse("_since_last_reset")
  lazy val targetConnector: String = AppConfigWrapper.getConfigStringOption(METRICS_TARGET_CONNECTOR).getOrElse("-")

  def createAndAddMetricsReporter(name: String): PrometheusReporter = {
    val rep = new PrometheusReporter()
    Kamon.registerModule(name, rep)
    rep
  }

  override def getFixedMetrics: List[MetricDetail] = {
    buildMetrics(fixedMetricsData)
  }

  override def getResetMetrics: List[MetricDetail] =
    buildMetrics(resetBasedMetricsData, isResetMetrics = true)

  def resetMetrics(): Unit = {
    resetBasedReporter = createAndAddMetricsReporter(RESET_BASED_REPORTER_NAME)
  }

  private def getCleanedInputStr(inputStr: String): String = inputStr.stripMargin.replaceAll("\n", " ")

  def buildMetrics(inputStr: String, isResetMetrics: Boolean = false): List[MetricDetail] = {
    inputStr.split("\n").
      filter(l => l.nonEmpty).
      filterNot(_.startsWith("#")).
      toList.map { metricLine =>
        buildMetric(metricLine, isResetMetrics)
    }
  }

  def buildMetric(inputStr: String, isResetMetrics: Boolean = false): MetricDetail = {
    val cleanedInputStr = getCleanedInputStr(inputStr)
    val parsedMetricParam = parse(cleanedInputStr, isResetMetrics)

    val name = parsedMetricParam.name
    val value = parsedMetricParam.value
    val target = buildTarget(parsedMetricParam)
    val tags = Option(parsedMetricParam.tags)
    MetricDetail(name, target, value, tags)
  }


  private def buildName(actualName: String, isResetMetrics: Boolean): String = {
    if(isResetMetrics) s"""$actualName$resetMetricsNameSuffix""" else actualName
  }

  private def parse(inputStr: String, isResetMetrics: Boolean): ParsedMetricParam = {
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
    ParsedMetricParam(buildName(name, isResetMetrics), value.trim.toDouble, tagString.map(parseTags).getOrElse(Map.empty))
  }

  private def parseTags(tagString: String): Map[String, String] = {
    val result = tagString
      .trim
      .split("\",")
      .map { token =>
        val splitted = token.split("=")
        assert(splitted.size == 2, "unsupported token: " + token + s" (tagString: $tagString)")
        splitted(0).trim -> splitted(1).trim.replace("\"", "")
      }.toMap
    result
  }

  private def buildTarget(parsedMetricParam: ParsedMetricParam): String = {
    val name = parsedMetricParam.name
    TargetBuilder.targetBuilders.find(tb => name.startsWith(tb._1) || name.contains(tb._1)).map { case (_, tb) =>
      tb.buildTarget(parsedMetricParam.tags)
    }.getOrElse(DEFAULT_TARGET)
  }

}

trait TargetBuilder {

  def getConfiguredTagsByTargetType(typ: String): Option[Set[String]] = AppConfigWrapper.getConfigSetOfStringOption(typ)

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