package com.evernym.verity.event_bus.adapters.basic

import akka.Done
import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.event_bus.adapters.basic.consumer.BasicConsumerAdapter
import com.evernym.verity.event_bus.adapters.basic.event_store.BasicEventStoreAPI
import com.evernym.verity.event_bus.adapters.basic.producer.BasicProducerAdapter
import com.evernym.verity.event_bus.event_handlers.RequestSourceUtil
import com.evernym.verity.event_bus.ports.consumer.{Message, MessageHandler}
import com.evernym.verity.integration.base.PortProvider
import com.evernym.verity.protocol.engine.ProtoRef
import com.evernym.verity.testkit.{BasicSpec, HasExecutionContext}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.core.provider.EventFormatProvider
import io.cloudevents.jackson.JsonFormat
import org.json.JSONObject
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import java.net.URI
import java.time.OffsetDateTime.now
import java.time.ZoneId
import java.util.UUID

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


class BasicEventStoreSpec
  extends BasicSpec
    with HasExecutionContext
    with Eventually {

  "BasicEventStore" - {
    "when started" - {
      "should be successful" in {
        startEventStore()
      }
    }

    "when tried to publish messages to topics before consumer is ready" - {
      "should be successful" in {
        val producer = createBasicProducerAdapter()

        val event1 = buildEvent(
          RequestSourceUtil.build("domainId", "relId", "pinstid11", "threadid1", ProtoRef("write-schema", "0.6")),
          "event-type-1",
          """{"field1": "value1"}"""
        )
        producer.send("topic1", event1)

        val event2 = buildEvent(
          RequestSourceUtil.build("domainId", "relId", "pinstid12", "threadid2", ProtoRef("write-schema", "0.6")),
          "event-type-2",
          """{"field1": "value1"}"""
        )
        producer.send("topic2", event2)

        checkMessages("topic1", 0)
        checkMessages("topic2", 0)
      }
    }

    "when consumer started subscribing" - {
      "should be successful and receive messages if exists" in {
        val consumer = createBasicConsumerAdapter(MockMsgHandler)
        Await.result(consumer.start(), 15.seconds)
        checkMessages("topic1", 1)
        checkMessages("topic2", 1)
      }
    }

    "when tried to publish messages to topic1" - {
      "should be successful" in {
        val producer = createBasicProducerAdapter()
        val event = buildEvent(
          RequestSourceUtil.build("domainId", "relId", "pinstid1", "threadid1", ProtoRef("write-schema", "0.6")),
          "event-type-1",
          """{"field1": "value1"}"""
        )
        producer.send("topic1", event)
        checkMessages("topic1", 1)
        checkMessages("topic2", 0)
      }
    }

    "when tried to publish messages to topic2" - {
      "should be successful" in {
        val producer = createBasicProducerAdapter()
        val event = buildEvent(
          RequestSourceUtil.build("domainId", "relId", "pinstid2", "threadid2", ProtoRef("write-cred-def", "0.6")),
          "event-type-2",
          """{"field1": "value1"}"""
        )
        producer.send("topic2", event)
        checkMessages("topic1", 0)
        checkMessages("topic2", 1)
        checkMessages("topic1", 0)
        checkMessages("topic2", 0)
      }
    }
  }

  private def checkMessages(topicName: String, expectedSize: Int): Unit = {
    eventually(timeout(Span(15, Seconds)), interval(Span(200, Millis))) {
      MockMsgHandler.getMessagesAndReset(topicName).size shouldBe expectedSize
    }
  }

  val config: Config = {
    val port = PortProvider.getFreePort
    ConfigFactory.parseString(
      s"""
         |akka {
         |  actor {
         |    provider = "akka.cluster.ClusterActorRefProvider"
         |  }
         |  remote.artery {
         |    canonical {
         |      hostname = "127.0.0.1"
         |      port = $port
         |    }
         |  }
         |
         |  cluster {
         |    seed-nodes = [
         |      "akka://event-store@127.0.0.1:$port",
         |    ]
         |    downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
         |    jmx.multi-mbeans-in-same-jvm = on
         |  }
         |}
         |verity {
         |  event-bus {
         |    basic {
         |      store {
         |        http-listener {
         |          host = "localhost"
         |          port = ${PortProvider.getFreePort}
         |        }
         |      }
         |      consumer {
         |        id = "verity"
         |        topics = ["topic1", "topic2"]
         |
         |        http-listener {
         |          host = "localhost"
         |          port = ${PortProvider.getFreePort}
         |        }
         |      }
         |    }
         |  }
         |}
         |""".stripMargin
    )
  }

  lazy val actorSystem: ActorSystem = ActorSystemVanilla("event-store", config, seedNodesWithRandomPorts = false)

  private def createBasicConsumerAdapter(msgHandler: MessageHandler): BasicConsumerAdapter = {
    new BasicConsumerAdapter(new TestAppConfig(Option(config)), msgHandler)(actorSystem, executionContext)
  }

  private def createBasicProducerAdapter(): BasicProducerAdapter = {
    new BasicProducerAdapter(new TestAppConfig(Option(config)))(actorSystem, executionContext)
  }

  private def buildEvent(source: String, typ: String, jsonPayload: String): Array[Byte] = {
    val event = CloudEventBuilder.v1()
      .withId(UUID.randomUUID().toString)
      .withType(typ)
      .withSource(URI.create(source))
      .withData("application/json", jsonPayload.getBytes())
      .withTime(now(ZoneId.of("UTC")))
      .build()

    EventFormatProvider
      .getInstance
      .resolveFormat(JsonFormat.CONTENT_TYPE)
      .serialize(event)
  }

  private def startEventStore(): BasicEventStoreAPI = {
    new BasicEventStoreAPI(config)(actorSystem, executionContext)
  }
}

object MockMsgHandler extends MessageHandler {

  override def handleMessage(message: Message): Future[Done] = synchronized {
    val topicName = message.metadata.topic
    val existingMessages = receivedMessages.getOrElse(topicName, List.empty)
    val updatedMessages = existingMessages :+ message.cloudEvent
    receivedMessages = receivedMessages + (topicName -> updatedMessages)
    Future.successful(Done)
  }

  def getMessagesAndReset(topicName: TopicName): List[JSONObject] = synchronized {
    val existingMessages = receivedMessages.getOrElse(topicName, List.empty)
    receivedMessages = receivedMessages + (topicName -> List.empty)
    existingMessages
  }

  var receivedMessages: Map[TopicName, List[JSONObject]] = Map.empty
  type TopicName = String
}
