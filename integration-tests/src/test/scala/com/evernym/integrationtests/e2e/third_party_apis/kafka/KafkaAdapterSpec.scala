package com.evernym.integrationtests.e2e.third_party_apis.kafka

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.kafka.ProducerSettings
import akka.kafka.testkit.KafkaTestkitTestcontainersSettings
import akka.kafka.testkit.scaladsl.TestcontainersKafkaPerClassLike
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.event_bus.adapters.consumer.kafka.ConsumerSettingsProvider
import com.evernym.verity.event_bus.ports.consumer.{Event, EventHandler, Message}
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.scalatest.time.{Millis, Seconds, Span}

import scala.jdk.CollectionConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


/**
 * goal behind this spec is to test KafkaProducerAdapter and KafkaConsumerAdapter
 * depends on docker environment
 */
class KafkaAdapterSpec
  extends KafkaSpecBase
    with TestcontainersKafkaPerClassLike {

  val TOPIC_NAME_1 = "endorser-txn-events"
  val TOPIC_NAME_2 = "endorser-activeness-events"

  val BROKERS_COUNT = 2
  val REPLICATION_FACTOR = BROKERS_COUNT  //should be less than or equal to the `BROKERS_COUNT`


  val topic1MsgBatch1 = (1 to 20).map(i => Message(s"""{"context":"{"batch-id": "1", "pinstid":"pinst-$i"}""", s"topic1-payload-$i"))
  val topic2MsgBatch1 = (1 to 30).map(i => Message(s"""{"context":"{"batch-id": "1", "pinstid":"pinst-$i"}""", s"topic2-payload-$i"))
  val topic1MsgBatch2 = (1 to 35).map(i => Message(s"""{"context":"{"batch-id": "2", "pinstid":"pinst-$i"}""", s"topic1-payload-$i"))
  val topic2MsgBatch2 = (1 to 40).map(i => Message(s"""{"context":"{"batch-id": "2", "pinstid":"pinst-$i"}""", s"topic2-payload-$i"))


  "KafkaContainer" - {
    "when checked status" - {
      "should be ready" in {
        brokerContainers.size shouldBe BROKERS_COUNT
      }
    }
  }

  "KafkaConsumerAdapter" - {

    "when tried to consume published messages" - {
      "should be successful" in {
        //create few consumers
        val consumer1 = {
          val consumerSettingsProvider = createConsumerSettingsProvider(
            brokerContainers.head.getBootstrapServers, Seq(TOPIC_NAME_1, TOPIC_NAME_2))
          createConsumer(new MockEventHandler("1"), consumerSettingsProvider)
        }
        val consumer2 = {
          val consumerSettingsProvider = createConsumerSettingsProvider(
            brokerContainers.head.getBootstrapServers, Seq(TOPIC_NAME_1, TOPIC_NAME_2))
          createConsumer(new MockEventHandler("2"), consumerSettingsProvider)
        }
        val eventProcessors = List(consumer1, consumer2).map(_.eventHandler.asInstanceOf[MockEventHandler])
        eventProcessors.flatMap(_.getEvents).size shouldBe 0

        //publish first batch of messages to the event bus

        val allMsgs = topic1MsgBatch1 ++ topic2MsgBatch1

        publishEvents(producerSettings, TOPIC_NAME_1, topic1MsgBatch1.map(msg => new String(msg.toByteArray)))
        publishEvents(producerSettings, TOPIC_NAME_2, topic2MsgBatch1.map(msg => new String(msg.toByteArray)))

        //confirm consumers are able to receive those published messages and offset is committed accordingly
        eventually(timeout(Span(10, Seconds)), interval(Span(200, Millis))) {
          eventProcessors.flatMap(_.getEvents).size shouldBe allMsgs.size
          eventProcessors.flatMap(_.getEvents).filter(_.metadata.topic == TOPIC_NAME_1).map(_.message) shouldBe topic1MsgBatch1
          eventProcessors.flatMap(_.getEvents).filter(_.metadata.topic == TOPIC_NAME_2).map(_.message) shouldBe topic2MsgBatch1
          eventProcessors.flatMap(_.getEvents).filter(_.metadata.topic == TOPIC_NAME_1).map(_.metadata.offset).max shouldBe topic1MsgBatch1.size - 1
          eventProcessors.flatMap(_.getEvents).filter(_.metadata.topic == TOPIC_NAME_2).map(_.metadata.offset).max shouldBe topic2MsgBatch1.size - 1
        }

        //stop the current active consumers
        Await.result(consumer1.stop(), 15.seconds)
        Await.result(consumer2.stop(), 15.seconds)
      }
    }

    "when tried to consume new published messages with new consumers" - {
      "should be successful" in {
        //start new consumers
        val consumer3 = {
          val consumerSettingsProvider = createConsumerSettingsProvider(
            brokerContainers.head.getBootstrapServers, Seq(TOPIC_NAME_1, TOPIC_NAME_2))
          createConsumer(new MockEventHandler("3"), consumerSettingsProvider)
        }
        val consumer4 = {
          val consumerSettingsProvider = createConsumerSettingsProvider(
            brokerContainers.head.getBootstrapServers, Seq(TOPIC_NAME_1, TOPIC_NAME_2))
          createConsumer(new MockEventHandler("4"), consumerSettingsProvider)
        }
        val eventProcessors = List(consumer3, consumer4).map(_.eventHandler.asInstanceOf[MockEventHandler])

        //publish second batch of messages to the event bus
        val allMsgs = topic1MsgBatch2 ++ topic2MsgBatch2

        publishEvents(producerSettings, TOPIC_NAME_1, topic1MsgBatch2.map(msg => new String(msg.toByteArray)))
        publishEvents(producerSettings, TOPIC_NAME_2, topic2MsgBatch2.map(msg => new String(msg.toByteArray)))

        //confirm new consumers are able to receive those newly published messages and offset is committed accordingly
        eventually(timeout(Span(15, Seconds)), interval(Span(200, Millis))) {
          eventProcessors.flatMap(_.getEvents).size shouldBe allMsgs.size
          eventProcessors.flatMap(_.getEvents).filter(_.metadata.topic == TOPIC_NAME_1).map(_.message) shouldBe topic1MsgBatch2
          eventProcessors.flatMap(_.getEvents).filter(_.metadata.topic == TOPIC_NAME_2).map(_.message) shouldBe topic2MsgBatch2
          eventProcessors.flatMap(_.getEvents).filter(_.metadata.topic == TOPIC_NAME_1).map(_.metadata.offset).max shouldBe (topic1MsgBatch1 ++ topic1MsgBatch2).size - 1
          eventProcessors.flatMap(_.getEvents).filter(_.metadata.topic == TOPIC_NAME_2).map(_.metadata.offset).max shouldBe (topic2MsgBatch1 ++ topic2MsgBatch2).size - 1
        }

        //stop the current active consumers
        Await.result(consumer3.stop(), 15.seconds)
        Await.result(consumer4.stop(), 15.seconds)
      }
    }
  }

  lazy val producerSettings: ProducerSettings[String, String] = createProducerSettings(getDefaultProducerSettings())

  lazy val defaultAkkaKafkaConfig: Config = ConfigFactory.load().withOnlyPath("akka.kafka")

  lazy val verityKafkaConsumerConfig: Config = ConfigFactory.parseString(
    """
      | verity.kafka = ${akka.kafka} {
      |   consumer = ${akka.kafka.consumer} {
      |     kafka-clients = ${akka.kafka.consumer.kafka-clients} {
      |       bootstrap.servers = "testkafka"
      |       group.id = "verity"
      |       client.id = "verity"
      |       auto.offset.reset: "earliest"
      |       session.timeout.ms: 60000
      |     }
      |
      |     msg-handling-parallelism = 10
      |     stop-timeout = 5 seconds
      |   }
      | }
      |""".stripMargin
  )

  implicit lazy val actorSystem: ActorSystem[Nothing] = ActorSystemVanilla("test").toTyped

  def createConsumerSettingsProvider(bootstrapServer: String,
                                     topics: Seq[String]):  ConsumerSettingsProvider = {
    val config = verityKafkaConsumerConfig
      .withFallback(defaultAkkaKafkaConfig)
      .resolve()
      .withValue("verity.kafka.consumer.kafka-clients.bootstrap.servers", ConfigValueFactory.fromAnyRef(bootstrapServer))
      .withValue("verity.kafka.consumer.topics", ConfigValueFactory.fromIterable(topics.asJava))

    ConsumerSettingsProvider(config)
  }

  override val testcontainersSettings = KafkaTestkitTestcontainersSettings(actorSystem.classicSystem)
    .withNumBrokers(BROKERS_COUNT)
    .withInternalTopicsReplicationFactor(REPLICATION_FACTOR)
    .withConfigureKafka { brokerContainers =>
      brokerContainers.foreach(_.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true"))
    }

}

/**
 *
 * @param id a unique id for this event processor (mostly may used for debugging/troubleshooting purposes only)
 */
class MockEventHandler(id: String) extends EventHandler {
  var events = List.empty[Event]

  override def handleEvent(event: Event): Future[Unit] = {
    events = events :+ event
    Future.successful(())
  }

  def getEvents: List[Event] = events
}