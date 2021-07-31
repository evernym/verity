package com.evernym.verity.actor.metrics

import akka.actor.{Actor, ActorSystem}
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.{MetricsWriter, MetricsWriterExtension}
import com.evernym.verity.util.JsonUtil.deserializeJsonStringToObject
import com.evernym.verity.util2.Exceptions
import org.hyperledger.indy.sdk.metrics.Metrics

import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

class LibindyMetricsCollector(implicit val actorSystem: ActorSystem) extends Actor {

  private val logger = getLoggerByClass(getClass)

  val metricsWriter: MetricsWriter = MetricsWriterExtension(context.system).get()

  final override def receive: Receive = {
    case CollectLibindyMetrics() => this.collectLibindyMetrics()
  }

  def collectLibindyMetrics(): Unit = {
    val replyTo = sender()
    val delayFuture = akka.pattern.after(10.seconds, using = actorSystem.scheduler)(
      Future.failed(new Exception("Metrics was not collected in 10s interval"))
    )
    val metricsFuture = toFuture(Metrics.collectMetrics)
    Future.firstCompletedOf(Seq(metricsFuture, delayFuture)).onComplete {
      result => {
        result.flatMap {
          metrics => {
            Try {
              val metricsObj: Map[String, List[LibindyMetricsRecord]] = deserializeJsonStringToObject[Map[String, List[LibindyMetricsRecord]]](metrics)
              metricsObj foreach (metricsItem => {
                val metricsName = metricsItem._1
                val metricsList = metricsItem._2
                metricsList foreach (
                  metricsRecord => {
                    //TODO: need to fix this soon (VE-2763)
                    //metricsWriter.gaugeUpdate(s"libindy_$metricsName", metricsRecord.value, metricsRecord.tags)
                  }
                )
              })
            }
          }
        } match {
          case Success(_) =>
            replyTo ! CollectLibindySuccess()
          case Failure(e) =>
            logger.warn(Exceptions.getStackTraceAsSingleLineString(e))
            replyTo ! CollectLibindyFailed(e.getMessage)
        }
      }
    }
  }
}

case class LibindyMetricsRecord(value: Double, tags: Map[String, String])

case class CollectLibindyMetrics() extends ActorMessage

case class CollectLibindySuccess() extends ActorMessage

case class CollectLibindyFailed(e: String) extends ActorMessage
