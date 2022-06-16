package com.evernym.verity.eventing.adapters.kafka.consumer

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
        val akkaKafkaConfig = ConfigFactory.parseString(
          """
            | akka.kafka {
            |   consumer {
            |     kafka-clients {
            |       bootstrap.servers = ${verity.kafka.common.bootstrap-servers}
            |       client.id = ${verity.kafka.common.client.id}
            |       group.id = ${verity.kafka.consumer.group.id}
            |       auto.offset.reset: "earliest"
            |       session.timeout.ms: 60000
            |     }
            |     stop-timeout = 5 seconds
            |   }
            | }
            |""".stripMargin
        ).withFallback(ConfigFactory.load())

        val verityKafkaConfig = ConfigFactory.parseString(
            """
              | verity.kafka {
              |   common {
              |      bootstrap-servers = "testkafka"
              |      user-name = ""
              |      password = ""
              |      client.id = "verity"
              |
              |      # <K8S_SKIP>
              |      sasl.jaas.config="org.apache.kafka.common.security.plain.PlainLoginModule   required username='"${?verity.kafka.common.user-name}"'   password='"${?verity.kafka.common.password}"';"
              |    }
              |
              |    consumer {
              |      group.id = "verity"
              |      topics = ["endorsement"]
              |      msg-handling-parallelism = 10
              |    }
              | }
              |""".stripMargin
            )

        val settingsProvider = ConsumerSettingsProvider(verityKafkaConfig.withFallback(akkaKafkaConfig).resolve())
        settingsProvider.topics shouldBe List("endorsement")

        val kafkaConsumerSettings = settingsProvider.kafkaConsumerSettings()
        Map(
          "bootstrap.servers"   -> "testkafka",
          "group.id"            -> "verity",
          "client.id"           -> "verity",
          "enable.auto.commit"  -> "false",
          "auto.offset.reset"   -> "earliest",
          "session.timeout.ms"  -> "60000"
        ).toSet.subsetOf(kafkaConsumerSettings.properties.toSet) shouldBe true

        kafkaConsumerSettings.keyDeserializerOpt.get shouldBe a[StringDeserializer]
        kafkaConsumerSettings.valueDeserializerOpt.get shouldBe a[ByteArrayDeserializer]

        val kafkaCommitterSettings = settingsProvider.kafkaCommitterSettings()
        kafkaCommitterSettings.maxBatch shouldBe 10
        kafkaCommitterSettings.maxInterval shouldBe FiniteDuration(10000, MILLISECONDS)
        kafkaCommitterSettings.parallelism shouldBe 100
        kafkaCommitterSettings.delivery shouldBe CommitDelivery.WaitForAck
        kafkaCommitterSettings.when shouldBe CommitWhen.offsetFirstObserved
      }
    }
  }
}
