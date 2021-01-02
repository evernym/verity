package com.evernym.verity.actor.metrics

import akka.Done
import akka.actor.Actor
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.{ActorMessage, MetricsFilterCriteria}
import com.evernym.verity.metrics.{MetricsReader, MetricsWriter}
import com.evernym.verity.util.JsonUtil.deserializeJsonStringToMap
import com.evernym.verity.util.Util.logger
import org.hyperledger.indy.sdk.metrics.Metrics
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext

import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

case class CollectLibindyMetrics() extends ActorMessage
case class CollectLibindySuccess() extends ActorMessage
case class CollectLibindyFailed(e: String) extends ActorMessage

class LibindyMetricsCollector extends Actor {
  def collectLibindyMetrics(): Unit = {
    val replyTo = sender()
    toFuture(Metrics.collectMetrics).onComplete {
      case Success(metrics) =>
        deserializeJsonStringToMap[String, Integer](metrics) foreach (
          metrics_item => MetricsWriter.gaugeApi.update(s"libindy_${metrics_item._1}", metrics_item._2.longValue())
          )
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