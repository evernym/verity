package com.evernym.verity.eventing.adapters.kafka.producer

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
        val akkaKafkaConfig = ConfigFactory.parseString(
          """
            | akka.kafka {
            |   producer {
            |     kafka-clients {
            |       bootstrap.servers = ${verity.kafka.common.bootstrap-servers}
            |       client.id = ${verity.kafka.common.client.id}
            |     }
            |     resolve-timeout = 3 seconds
            |     parallelism = 100
            |   }
            |   committer {
            |   }
            | }
            |""".stripMargin
        ).withFallback(ConfigFactory.load())

        val verityKafkaConfig = ConfigFactory.parseString(
            """
              | verity.kafka  {
              |   common {
              |      bootstrap-servers = "testkafka"
              |      user-name = ""
              |      password = ""
              |      client.id = "verity"
              |
              |      # <K8S_SKIP>
              |      sasl.jaas.config="org.apache.kafka.common.security.plain.PlainLoginModule   required username='"${?verity.kafka.common.user-name}"'   password='"${?verity.kafka.common.password}"';"
              |   }
              | }
              |""".stripMargin
            )

        val settingsProvider = ProducerSettingsProvider(verityKafkaConfig.withFallback(akkaKafkaConfig).resolve())

        val kafkaProducerSettings = settingsProvider.kafkaProducerSettings()
        Map(
          "bootstrap.servers"   -> "testkafka",
          "client.id"           -> "verity",
        ).toSet.subsetOf(kafkaProducerSettings.properties.toSet) shouldBe true
        kafkaProducerSettings.keySerializerOpt.get shouldBe a[StringSerializer]
        kafkaProducerSettings.valueSerializerOpt.get shouldBe a[ByteArraySerializer]
      }
    }
  }
}
