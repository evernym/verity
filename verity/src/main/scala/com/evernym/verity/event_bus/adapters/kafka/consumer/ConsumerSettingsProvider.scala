package com.evernym.verity.event_bus.adapters.kafka.consumer

import akka.kafka.{CommitterSettings, ConsumerSettings}
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.typesafe.config.Config
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}

import scala.jdk.CollectionConverters._

case object ConsumerSettingsProvider {

  def apply(config: Config): ConsumerSettingsProvider = {
    new ConsumerSettingsProvider(config)
  }
}

/**
 * Consumer settings should look like this (inherited from `akka.kafka.consumer`)
 *
   verity.kafka = ${akka.kafka} {

     # https://github.com/akka/alpakka-kafka/blob/v3.0.0/core/src/main/resources/reference.conf#L50
     consumer = ${akka.kafka.consumer} {

       # https://github.com/akka/alpakka-kafka/blob/v3.0.0/core/src/main/resources/reference.conf#L99
       kafka-clients = ${akka.kafka.consumer.kafka-clients} {
         bootstrap.servers = "testkafka"
         group.id = "verity"
         client.id = "verity"
         auto.offset.reset: "earliest"
         session.timeout.ms: 60000
       }

       # override verity specific consumer configurations
       stop-timeout = 5 seconds

       # verity consumer adapter configuration
       topics = ["endorsement"]
       msg-handling-parallelism = 10
     }

     # https://github.com/akka/alpakka-kafka/blob/v3.0.0/core/src/main/resources/reference.conf#L165-L190
     # override verity specific configurations
     committer = ${akka.kafka.committer} {
       max-batch = 10
     }
   }
 *
 */
final class ConsumerSettingsProvider(config: Config) {
  val verityKafkaConfigReader: ConfigReadHelper = ConfigReadHelper(config.getConfig("verity.kafka").resolve())

  val topics: Seq[String] = verityKafkaConfigReader.getStringListReq("consumer.topics")
  val msgHandlingParallelism: Int = verityKafkaConfigReader.getIntOption("consumer.msg-handling-parallelism").getOrElse(10)

  val consumerConfig: Config =
    verityKafkaConfigReader
      .getConfigOption("consumer")
      .getOrElse(throw new RuntimeException("required config not found at path: verity.kafka.consumer"))

  val committerConfig: Config =
    verityKafkaConfigReader
      .getConfigOption("committer")
      .getOrElse(throw new RuntimeException("required config not found at path: verity.kafka.committer"))

  validateConfig()

  def validateConfig(): Unit = {
    val kafkaClientConfigs = consumerConfig
      .getConfig("kafka-clients")
      .entrySet().asScala.map(r => r.getKey -> r.getValue.unwrapped().toString).toMap

    val requiredKafkaClientProperties = Set("bootstrap.servers", "group.id", "client.id", "auto.offset.reset", "session.timeout.ms")

    if (!requiredKafkaClientProperties.subsetOf(kafkaClientConfigs.keySet)) {
      throw new RuntimeException("required kafka client properties not found (at path: verity.kafka.consumer.kafka-clients): " + requiredKafkaClientProperties.diff(kafkaClientConfigs.keySet).mkString(", "))
    }
    val invalidReqConfigs = kafkaClientConfigs.filter{case (k, v) => v == null || v.isEmpty}
    if (invalidReqConfigs.nonEmpty) {
      throw new RuntimeException("required kafka client properties cannot be empty/null (at path: verity.kafka.consumer.kafka-clients): " + invalidReqConfigs.keySet.mkString(", "))
    }
  }

  def kafkaConsumerSettings(): ConsumerSettings[String, Array[Byte]] = {
    ConsumerSettings(consumerConfig, new StringDeserializer, new ByteArrayDeserializer)
  }

  def kafkaCommitterSettings(): CommitterSettings = {
    CommitterSettings(committerConfig)
  }
}