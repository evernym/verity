package com.evernym.verity.actor.metrics

import akka.Done
import akka.actor.Actor
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.util.JsonUtil.deserializeJsonStringToMap
import com.evernym.verity.util.Util.logger
import org.hyperledger.indy.sdk.metrics.Metrics
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.util.{Failure, Success}

case class CollectLibindyMetrics() extends ActorMessageClass
case class CollectLibindyFailed(e: String) extends ActorMessageClass

class LibindyMetricsCollector extends Actor {
  def collectLibindyMetrics(): Any = {
    toFuture(Metrics.collectMetrics).onComplete {
      case Success(metrics) =>
        // TODO: change Integer to a bigger value. Integer is not enough for timestamp
        val metricsResult : Map [String, Integer] = deserializeJsonStringToMap[String, Integer](metrics)
        metricsResult foreach (
          metrics_item => MetricsWriter.gaugeApi.updateWithTags(metrics_item._1, metrics_item._2.longValue())
          )
      case Failure(e) =>
        logger.debug(Exceptions.getStackTraceAsSingleLineString(e))
        return CollectLibindyFailed(e.getMessage)
    }
    Done
  }

  final override def receive = {
    case CollectLibindyMetrics() => sender ! this.collectLibindyMetrics()
  }

}