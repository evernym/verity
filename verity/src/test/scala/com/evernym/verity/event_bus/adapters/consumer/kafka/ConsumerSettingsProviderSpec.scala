package com.evernym.verity.event_bus.adapters.consumer.kafka

import akka.kafka.{CommitDelivery, CommitWhen}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}


class ConsumerSettingsProviderSpec
  extends BasicSpec {

  "ConsumerSettingsProvider" - {

    "when constructed without sufficient configs" - {
      "should fail" in {
        intercept[Missing] {
          ConsumerSettingsProvider(ConfigFactory.empty)
        }
      }
    }

    "when constructed with sufficient configs" - {
      "should pass" in {
        val defaultAkkaKafkaConfig = ConfigFactory.load().withOnlyPath("akka.kafka")

        val verityKafkaConfig = ConfigFactory.parseString(
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
              |     stop-timeout = 5 seconds
              |
              |     topics = ["endorsement"]
              |     msg-handling-parallelism = 10
              |   }
              |
              |   committer = ${akka.kafka.committer} {
              |   }
              | }
              |""".stripMargin
            )

        val settingsProvider = ConsumerSettingsProvider(verityKafkaConfig.withFallback(defaultAkkaKafkaConfig).resolve())
        settingsProvider.topics shouldBe List("endorsement")

        val kafkaConsumerSettings = settingsProvider.kafkaConsumerSettings()
        kafkaConsumerSettings.properties shouldBe Map(
          "bootstrap.servers"   -> "testkafka",
          "group.id"            -> "verity",
          "client.id"           -> "verity",
          "enable.auto.commit"  -> "false",
          "auto.offset.reset"   -> "earliest",
          "session.timeout.ms"  -> "60000"
        )
        kafkaConsumerSettings.keyDeserializerOpt.get shouldBe a[StringDeserializer]
        kafkaConsumerSettings.valueDeserializerOpt.get shouldBe a[ByteArrayDeserializer]

        val kafkaCommitterSettings = settingsProvider.kafkaCommitterSettings()
        kafkaCommitterSettings.maxBatch shouldBe 1000
        kafkaCommitterSettings.maxInterval shouldBe FiniteDuration(10000, MILLISECONDS)
        kafkaCommitterSettings.parallelism shouldBe 100
        kafkaCommitterSettings.delivery shouldBe CommitDelivery.WaitForAck
        kafkaCommitterSettings.when shouldBe CommitWhen.offsetFirstObserved
      }
    }
  }
}
