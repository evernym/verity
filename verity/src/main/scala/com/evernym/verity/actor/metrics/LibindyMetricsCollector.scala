package com.evernym.verity.actor.metrics

import akka.actor.Actor
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.util.Util.logger
import org.hyperledger.indy.sdk.metrics.Metrics
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util.JsonUtil.deserializeJsonStringToObject

import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.util.{Failure, Success}

class LibindyMetricsCollector extends Actor {

  final override def receive: Receive = {
    case CollectLibindyMetrics() => this.collectLibindyMetrics()
  }

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

}

case class LibindyMetricsRecord(value: Long, tags: Map[String, String])
case class CollectLibindyMetrics() extends ActorMessage
case class CollectLibindySuccess() extends ActorMessage
case class CollectLibindyFailed(e: String) extends ActorMessage
