package com.evernym.verity.eventing.adapters.kafka.consumer

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
 * Consumer settings should look like this
 *
   akka.kafka = {

     # https://github.com/akka/alpakka-kafka/blob/v3.0.0/core/src/main/resources/reference.conf#L50
     consumer = {

       # https://github.com/akka/alpakka-kafka/blob/v3.0.0/core/src/main/resources/reference.conf#L99
       kafka-clients =  {
         bootstrap.servers = ${verity.kafka.common.bootstrap-servers}
         client.id = ${verity.kafka.common.client.id}
         group.id = ${verity.kafka.consumer.group.id}
         auto.offset.reset: "earliest"
         session.timeout.ms: 60000
       }

       # override verity specific consumer configurations
       stop-timeout = 5 seconds
     }

     # https://github.com/akka/alpakka-kafka/blob/v3.0.0/core/src/main/resources/reference.conf#L165-L190
     committer =  {
       max-batch = 10
     }
   }

   verity.kafka = {
     common {
       bootstrap-servers = "testkafka"
       user-name = "verity"
       password = "verity"
       client.id = "verity"
       sasl.jaas.config="org.apache.kafka.common.security.plain.PlainLoginModule   required username='"${?verity.kafka.common.user-name}"'   password='"${?verity.kafka.common.password}"';"
     }

     consumer {
       group.id = "verity"
       topics = ["endorsement"]
       msg-handling-parallelism = 10
     }
   }
 *
 */
final class ConsumerSettingsProvider(config: Config) {
  val verityKafkaConfigReader: ConfigReadHelper = ConfigReadHelper(config.getConfig("verity.kafka"))
  val akkaKafkaConfigReader: ConfigReadHelper = ConfigReadHelper(config.getConfig("akka.kafka").resolve())

  val topics: Seq[String] = verityKafkaConfigReader.getStringListReq("consumer.topics")
  val msgHandlingParallelism: Int = verityKafkaConfigReader.getIntOption("consumer.msg-handling-parallelism").getOrElse(10)

  val consumerConfig: Config =
    akkaKafkaConfigReader
      .getConfigOption("consumer")
      .getOrElse(throw new RuntimeException("required config not found at path: kafka.consumer"))

  val committerConfig: Config =
    akkaKafkaConfigReader
      .getConfigOption("committer")
      .getOrElse(throw new RuntimeException("required config not found at path: kafka.committer"))

  validateConfig()

  def validateConfig(): Unit = {
    val kafkaClientConfigs = consumerConfig
      .getConfig("kafka-clients")
      .entrySet().asScala.map(r => r.getKey -> r.getValue.unwrapped().toString).toMap

    val requiredKafkaClientProperties = Set("bootstrap.servers", "group.id", "client.id", "auto.offset.reset", "session.timeout.ms")

    if (!requiredKafkaClientProperties.subsetOf(kafkaClientConfigs.keySet)) {
      throw new RuntimeException("required kafka client properties not found (at path: kafka.consumer.kafka-clients): " + requiredKafkaClientProperties.diff(kafkaClientConfigs.keySet).mkString(", "))
    }
    val invalidReqConfigs = kafkaClientConfigs.filter{case (k, v) => v == null || v.isEmpty}
    if (invalidReqConfigs.nonEmpty) {
      throw new RuntimeException("required kafka client properties cannot be empty/null (at path: kafka.consumer.kafka-clients): " + invalidReqConfigs.keySet.mkString(", "))
    }
  }

  def kafkaConsumerSettings(): ConsumerSettings[String, Array[Byte]] = {
    ConsumerSettings(consumerConfig, new StringDeserializer, new ByteArrayDeserializer)
  }

  def kafkaCommitterSettings(): CommitterSettings = {
    CommitterSettings(committerConfig)
  }
}