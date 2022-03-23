package com.evernym.verity.event_bus.adapters.producer.kafka

import akka.Done
import akka.actor.typed.{ActorSystem => TypedActorSystem}
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.Source
import com.evernym.verity.event_bus.ports.producer.ProducerPort
import com.evernym.verity.observability.logs.LoggingUtil
import com.typesafe.scalalogging.Logger
import org.apache.kafka.clients.producer.ProducerRecord

import scala.concurrent.{ExecutionContext, Future}

object KafkaProducerAdapter {
  def apply(settingsProvider: ProducerSettingsProvider)
           (implicit executionContext: ExecutionContext,
            actorSystem: TypedActorSystem[_]): KafkaProducerAdapter = {
    new KafkaProducerAdapter(settingsProvider)
  }
}

class KafkaProducerAdapter(settingsProvider: ProducerSettingsProvider)
                          (implicit executionContext: ExecutionContext,
                           actorSystem: TypedActorSystem[_]) extends ProducerPort {
  val logger: Logger = LoggingUtil.getLoggerByClass(getClass)

  val producerSettings: ProducerSettings[String, Array[Byte]] = settingsProvider.kafkaProducerSettings()

  val settingsWithProducer: ProducerSettings[String, Array[Byte]] = producerSettings.withProducer(
    producerSettings.createKafkaProducer()
  )

  override def send(topic: String, payload: Array[Byte]): Future[Done] = {
    Source.single(new ProducerRecord[String, Array[Byte]](topic, payload))
      .runWith(Producer.plainSink(settingsWithProducer))
  }
}
