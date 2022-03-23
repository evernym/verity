package com.evernym.verity.event_bus.adapters.producer.kafka

import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}


class ProducerSettingsProviderSpec
  extends BasicSpec {

  "ProducerSettingsProvider" - {

    "when constructed without sufficient configs" - {
      "should fail" in {
        intercept[Missing] {
          ProducerSettingsProvider(ConfigFactory.empty)
        }
      }
    }

    "when constructed with sufficient configs" - {
      "should pass" in {
        val defaultAkkaKafkaConfig = ConfigFactory.load().withOnlyPath("akka.kafka")

        val verityKafkaConfig = ConfigFactory.parseString(
            """
              | verity.kafka = ${akka.kafka} {
              |   producer = ${akka.kafka.producer} {
              |     kafka-clients = ${akka.kafka.producer.kafka-clients} {
              |       bootstrap.servers = "testkafka"
              |       client.id = "verity"
              |     }
              |
              |     resolve-timeout = 3 seconds
              |
              |     parallelism = 100
              |   }
              | }
              |""".stripMargin
            )

        val settingsProvider = ProducerSettingsProvider(verityKafkaConfig.withFallback(defaultAkkaKafkaConfig).resolve())

        val kafkaProducerSettings = settingsProvider.kafkaProducerSettings()
        kafkaProducerSettings.properties shouldBe Map(
          "bootstrap.servers"   -> "testkafka",
          "client.id"           -> "verity",
        )
        kafkaProducerSettings.keySerializerOpt.get shouldBe a[StringSerializer]
        kafkaProducerSettings.valueSerializerOpt.get shouldBe a[ByteArraySerializer]
      }
    }
  }
}
