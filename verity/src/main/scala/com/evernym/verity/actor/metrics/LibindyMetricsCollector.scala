package com.evernym.verity.actor.metrics

import akka.Done
import akka.actor.Actor
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.{ActorMessageClass, MetricsFilterCriteria}
import com.evernym.verity.metrics.{MetricsReader, MetricsWriter}
import com.evernym.verity.util.Util.logger
import org.hyperledger.indy.sdk.metrics.Metrics
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util.JsonUtil.deserializeJsonStringToObject
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.{DefaultScalaModule, ScalaObjectMapper}

import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

case class CollectLibindyMetrics() extends ActorMessageClass
case class CollectLibindySuccess() extends ActorMessageClass
case class CollectLibindyFailed(e: String) extends ActorMessageClass

class LibindyMetricsCollector extends Actor {
  def collectLibindyMetrics(): Unit = {
    val replyTo = sender()
    toFuture(Metrics.collectMetrics).onComplete {
      case Success(metrics) =>
        val metricsObj: Map[String, List[LibindyMetricsRecord]] = deserializeJsonStringToObject[Map[String, List[LibindyMetricsRecord]]](metrics)
        metricsObj foreach( metricsItem => {
          val metricsName = metricsItem._1
          val metricsList = metricsItem._2
          metricsList foreach(
            metricsRecord => {
              MetricsWriter.gaugeApi.updateWithTags(s"libindy_$metricsName", metricsRecord.value, metricsRecord.tags)
            }
          )
        })
        replyTo ! CollectLibindySuccess()
      case Failure(e) =>
        logger.warn(Exceptions.getStackTraceAsSingleLineString(e))
        replyTo ! CollectLibindyFailed(e.getMessage)
    }
  }

  final override def receive = {
    case CollectLibindyMetrics() => this.collectLibindyMetrics()
  }

}

case class LibindyMetricsRecord(value: Long, tags: Map[String, String])