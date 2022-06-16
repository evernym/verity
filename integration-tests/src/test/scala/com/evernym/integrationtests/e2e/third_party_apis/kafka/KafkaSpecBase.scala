package com.evernym.integrationtests.e2e.third_party_apis.kafka

import akka.Done
import akka.actor.typed.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.testkit.scaladsl.ScalatestKafkaSpec
import com.evernym.verity.eventing.adapters.kafka.consumer.{ConsumerSettingsProvider, KafkaConsumerAdapter}
import com.evernym.verity.eventing.adapters.kafka.producer.{KafkaProducerAdapter, ProducerSettingsProvider}
import com.evernym.verity.eventing.ports.consumer.MessageHandler
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.Config
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future


abstract class KafkaSpecBase(kafkaPort: Int)
  extends ScalatestKafkaSpec(kafkaPort)
    with BasicSpec
    with Matchers
    with ScalaFutures
    with Eventually {

  protected def this() = this(kafkaPort = -1)

  def createProducerSettings(config: Config): ProducerSettings[String,Array[Byte]] = {
    ProducerSettings(config, new StringSerializer, new ByteArraySerializer)
  }

  def publishEvents(producerSettingsProvider: ProducerSettingsProvider,
                    topic: String,
                    events: Seq[Array[Byte]])(implicit actorSystem: ActorSystem[Nothing]): Future[Done] = {
    val producer = new KafkaProducerAdapter(producerSettingsProvider)
    Future.traverse(events){event => producer.send(topic, event)}.map(_ => Done)
  }

  def createConsumer(eventHandler: MessageHandler,
                     settingsProvider: ConsumerSettingsProvider)
                    (implicit actorSystem: ActorSystem[Nothing]): KafkaConsumerAdapter = {
    val consumer = new KafkaConsumerAdapter(eventHandler, settingsProvider)
    consumer.start()
    consumer
  }

}