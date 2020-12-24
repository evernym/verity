package com.evernym.verity.actor.metrics

import java.io.ByteArrayOutputStream

import akka.Done
import akka.actor.Actor
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.{ActorMessageClass, MetricsFilterCriteria}
import com.evernym.verity.metrics.{MetricsReader, MetricsWriter}
import com.evernym.verity.util.JsonUtil.{deserializeJsonStringToMap, deserializeJsonToMap}
import com.evernym.verity.util.Util.logger
import org.hyperledger.indy.sdk.metrics.Metrics
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
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
        //init
        val objectMapper = new ObjectMapper() with ScalaObjectMapper
        objectMapper.registerModule(DefaultScalaModule)
        //deser
        val str = """[{"value": "0", "tags": {"name": "Alex", "age": "20"}}, {"value": "3", "tags": {"name": "Anna", "age": "32"}}]"""
        val nameObj:List[ExampleRecord] = objectMapper.readValue[List[ExampleRecord]](str)
        //ser
        val out = new ByteArrayOutputStream()
        objectMapper.writeValue(out, nameObj)
        val nameJson = out.toString

        println(nameObj)
        println("!!!")
        println(nameJson)

        nameObj foreach( temp => {
          println(temp.tags)
          println("AAA")
        })

        deserializeJsonStringToMap[String, List[JsonNode]](metrics) foreach (
          metricsItem => {
            val metricsName = metricsItem._1
            println(metricsItem._1)
            println("QQQ")
            val metricsList = metricsItem._2
            println(metricsList)

            metricsList foreach(
              metricsRecordString => {
                //val metricsRecord =LibindyMetricsRecord(metricsRecordString.get("value").asLong(),
                //  deserializeJsonToMap(metricsRecordString.get("tags")))
                //println(metricsRecord)
                println("WWW")
                //MetricsWriter.gaugeApi.updateWithTags(s"libindy_$metricsName", metricsRecord.value.toLong, metricsRecord.tags)
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

case class LibindyMetricsRecord(@JsonProperty("value") value: Long, @JsonProperty("tags") tags: Map[String, String])
case class ExampleRecord(value: String, tags: Map[String, String])

case class LibindyMetrics()