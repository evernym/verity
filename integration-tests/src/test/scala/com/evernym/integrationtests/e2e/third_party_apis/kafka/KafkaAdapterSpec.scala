package com.evernym.integrationtests.e2e.third_party_apis.kafka

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.kafka.testkit.KafkaTestkitTestcontainersSettings
import akka.kafka.testkit.scaladsl.TestcontainersKafkaPerClassLike
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.eventing.adapters.kafka.consumer.ConsumerSettingsProvider
import com.evernym.verity.eventing.adapters.kafka.producer.ProducerSettingsProvider
import com.evernym.verity.eventing.ports.consumer.{Message, MessageHandler}
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.scalatest.time.{Millis, Seconds, Span}
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.core.provider.EventFormatProvider
import io.cloudevents.jackson.JsonFormat
import org.json.JSONObject

import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import scala.jdk.CollectionConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


/**
 * goal behind this spec is to test KafkaProducerAdapter and KafkaConsumerAdapter
 * (this test depends on availability of docker environment and has NOT yet been added to run during the CI integration tests job)
 */
class KafkaAdapterSpec
  extends KafkaSpecBase
    with TestcontainersKafkaPerClassLike {

  val TOPIC_NAME_1 = "endorser-txn-events"
  val TOPIC_NAME_2 = "endorser-activeness-events"

  val BROKERS_COUNT = 2
  val REPLICATION_FACTOR = BROKERS_COUNT  //should be less than or equal to the `BROKERS_COUNT`


  val topic1MsgBatch1 = (1 to 20).map(i => createCloudEvent(s"pinstId$i", s"""{"key":"topic1-payload-$i"}"""))
  val topic2MsgBatch1 = (1 to 30).map(i => createCloudEvent(s"pinstId$i", s"""{"key":"topic1-payload-$i"}"""))
  val topic1MsgBatch2 = (1 to 35).map(i => createCloudEvent(s"pinstId$i", s"""{"key":"topic1-payload-$i"}"""))
  val topic2MsgBatch2 = (1 to 40).map(i => createCloudEvent(s"pinstId$i", s"""{"key":"topic1-payload-$i"}"""))


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
          createConsumer(new MockMessageHandler("1"), consumerSettingsProvider)
        }
        val consumer2 = {
          val consumerSettingsProvider = createConsumerSettingsProvider(
            brokerContainers.head.getBootstrapServers, Seq(TOPIC_NAME_1, TOPIC_NAME_2))
          createConsumer(new MockMessageHandler("2"), consumerSettingsProvider)
        }
        val eventProcessors = List(consumer1, consumer2).map(_.messageHandler.asInstanceOf[MockMessageHandler])
        eventProcessors.flatMap(_.getMessages).size shouldBe 0

        //publish first batch of messages to the event bus

        val allMsgs = topic1MsgBatch1 ++ topic2MsgBatch1

        publishEvents(producerSettingsProvider, TOPIC_NAME_1, topic1MsgBatch1.map(serializedCloudEvent))
        publishEvents(producerSettingsProvider, TOPIC_NAME_2, topic2MsgBatch1.map(serializedCloudEvent))

        //confirm consumers are able to receive those published messages and offset is committed accordingly
        eventually(timeout(Span(10, Seconds)), interval(Span(200, Millis))) {
          eventProcessors.flatMap(_.getMessages).size shouldBe allMsgs.size
          compareReceivedMsgs(0, topic1MsgBatch1.map(toJsonObject).toList, eventProcessors, TOPIC_NAME_1)
          compareReceivedMsgs(0, topic2MsgBatch1.map(toJsonObject).toList, eventProcessors, TOPIC_NAME_2)
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
          createConsumer(new MockMessageHandler("3"), consumerSettingsProvider)
        }
        val consumer4 = {
          val consumerSettingsProvider = createConsumerSettingsProvider(
            brokerContainers.head.getBootstrapServers, Seq(TOPIC_NAME_1, TOPIC_NAME_2))
          createConsumer(new MockMessageHandler("4"), consumerSettingsProvider)
        }
        val eventProcessors = List(consumer3, consumer4).map(_.messageHandler.asInstanceOf[MockMessageHandler])

        //publish second batch of messages to the event bus
        val allMsgs = topic1MsgBatch2 ++ topic2MsgBatch2

        publishEvents(producerSettingsProvider, TOPIC_NAME_1, topic1MsgBatch2.map(serializedCloudEvent))
        publishEvents(producerSettingsProvider, TOPIC_NAME_2, topic2MsgBatch2.map(serializedCloudEvent))

        //confirm new consumers are able to receive those newly published messages and offset is committed accordingly
        eventually(timeout(Span(15, Seconds)), interval(Span(200, Millis))) {
          eventProcessors.flatMap(_.getMessages).size shouldBe allMsgs.size
          compareReceivedMsgs(topic1MsgBatch1.size, topic1MsgBatch2.map(toJsonObject).toList, eventProcessors, TOPIC_NAME_1)
          compareReceivedMsgs(topic2MsgBatch1.size, topic2MsgBatch2.map(toJsonObject).toList, eventProcessors, TOPIC_NAME_2)
        }

        //stop the current active consumers
        Await.result(consumer3.stop(), 15.seconds)
        Await.result(consumer4.stop(), 15.seconds)
      }
    }
  }

  def compareReceivedMsgs(alreadyReceivedMsgs: Int,
                          expectedEvents: List[JSONObject],
                          eventProcessors: List[MockMessageHandler],
                          topicName: String): Unit = {
    val actualMessages = eventProcessors.flatMap(_.getMessages).filter(_.metadata.topic == topicName)
    actualMessages.map(_.metadata.offset).max shouldBe actualMessages.size + alreadyReceivedMsgs - 1 //as offset starts with 0
    val actualEvents = actualMessages.map(_.cloudEvent)
    actualEvents.map(_.toString).sorted shouldBe expectedEvents.map(_.toString).sorted
  }

  def createCloudEvent(sourceId: String, payload: String): CloudEvent = {
    CloudEventBuilder
      .v1()
      .withId(UUID.randomUUID().toString)
      .withType("example.event.type")
      .withSource(URI.create(s"http://example.com/$sourceId"))
      .withData("application/json", payload.getBytes())
      .withTime(OffsetDateTime.now(ZoneId.of("UTC")))
      .withExtension("company", 1)
      .build()
  }

  def serializedCloudEvent(event: CloudEvent): Array[Byte] = {
    EventFormatProvider
      .getInstance
      .resolveFormat(JsonFormat.CONTENT_TYPE)
      .serialize(event)
  }

  def toJsonObject(event: CloudEvent): JSONObject = {
    new JSONObject(new String(serializedCloudEvent(event)))
  }

  lazy val producerSettingsProvider: ProducerSettingsProvider = createProducerSettingsProvider(brokerContainers.head.getBootstrapServers)

  lazy val akkaKafkaConfig =
    ConfigFactory.parseString(
      """
        | akka.kafka {
        |   consumer {
        |     kafka-clients {
        |       security.protocol = PLAINTEXT
        |       bootstrap.servers = ${verity.kafka.common.bootstrap-servers}
        |       client.id = ${verity.kafka.common.client.id}
        |
        |       auto.offset.reset: "earliest"
        |       session.timeout.ms: 60000
        |     }
        |     stop-timeout = 5 seconds
        |   }
        |   producer {
        |     kafka-clients {
        |       security.protocol = PLAINTEXT
        |       bootstrap.servers = ${verity.kafka.common.bootstrap-servers}
        |       client.id = ${verity.kafka.common.client.id}
        |     }
        |   }
        | }
        |""".stripMargin
    ).withFallback(ConfigFactory.load().withOnlyPath("akka.kafka"))

  lazy val verityKafkaConsumerConfig: Config = ConfigFactory.parseString(
    """
      | akka.kafka.consumer.kafka-clients {
      |   group.id = ${verity.kafka.consumer.group.id}
      | }
      | verity.kafka {
      |   common {
      |     bootstrap-servers = "testkafka"
      |     client.id = "verity"
      |     auto.offset.reset: "earliest"
      |     session.timeout.ms: 60000
      |   }
      |   consumer {
      |     group.id = "verity"
      |     msg-handling-parallelism = 10
      |     topics = [""]
      |   }
      | }
      |""".stripMargin
  )

  lazy val verityKafkaProducerConfig: Config = ConfigFactory.parseString(
    """
      | verity.kafka {
      |   common {
      |     bootstrap-servers = "testkafka"
      |     client.id = "verity"
      |     auto.offset.reset: "earliest"
      |     session.timeout.ms: 60000
      |   }
      | }
      |""".stripMargin
  )


  val actorSystemConfig = verityKafkaConsumerConfig.withFallback(verityKafkaProducerConfig).withFallback(akkaKafkaConfig).resolve()
  implicit lazy val actorSystem: ActorSystem[Nothing] = ActorSystemVanilla("test", actorSystemConfig).toTyped

  def createConsumerSettingsProvider(bootstrapServer: String,
                                     topics: Seq[String]):  ConsumerSettingsProvider = {
    val config = verityKafkaConsumerConfig
      .withFallback(akkaKafkaConfig)
      .withValue("verity.kafka.common.bootstrap-servers", ConfigValueFactory.fromAnyRef(bootstrapServer))
      .withValue("verity.kafka.consumer.topics", ConfigValueFactory.fromIterable(topics.asJava))
      .resolve()
    ConsumerSettingsProvider(config)
  }

  def createProducerSettingsProvider(bootstrapServer: String): ProducerSettingsProvider = {
    val config = verityKafkaProducerConfig
      .withFallback(akkaKafkaConfig)
      .withValue("verity.kafka.common.bootstrap-servers", ConfigValueFactory.fromAnyRef(bootstrapServer))
      .resolve()
    ProducerSettingsProvider(config)
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
class MockMessageHandler(id: String) extends MessageHandler {
  var messages = List.empty[Message]

  override def handleMessage(message: Message): Future[Done] = {
    messages = messages :+ message
    Future.successful(Done)
  }

  def getMessages: List[Message] = messages
}
