package com.evernym.verity.event_bus.adapters.kafka

import akka.kafka.{CommitDelivery, CommitWhen}
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

class ConsumerSettingsProviderSpec
  extends BasicSpec {

  "ConsumerSettingsProvider" - {

    "when constructed without sufficient configs" - {
      "should fail" in {
        intercept[Missing] {
          ConsumerSettingsProvider(ActorSystemVanilla("test"))
        }
      }
    }

    "when constructed with sufficient configs" - {
      "should pass" in {
        val defaultAkkaKafkaConfig = ConfigFactory.load().withOnlyPath("akka.kafka")

        val verityKafkaConfig = ConfigFactory.parseString(
            """
              | verity.kafka.consumer: ${akka.kafka.consumer} {
              |   service-name = "testkafka"
              |   topics = ["endorsement"]
              |   group-id = "verity"
              | }
              |""".stripMargin
            )

        val settingsProvider = ConsumerSettingsProvider(ActorSystemVanilla("test", defaultAkkaKafkaConfig.withFallback(verityKafkaConfig).resolve()))
        settingsProvider.bootstrapServers shouldBe "testkafka"
        settingsProvider.topics shouldBe List("endorsement")
        settingsProvider.groupId shouldBe "verity"

        val kafkaConsumerSettings = settingsProvider.kafkaConsumerSettings()
        kafkaConsumerSettings.properties shouldBe Map(
          "enable.auto.commit"  -> "false",
          "bootstrap.servers"   -> "testkafka",
          "group.id"            -> "verity",
          "auto.offset.reset"   -> "earliest"
        )
        kafkaConsumerSettings.keyDeserializerOpt.get shouldBe a[StringDeserializer]
        kafkaConsumerSettings.valueDeserializerOpt.get shouldBe a[StringDeserializer]

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
