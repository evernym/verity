package com.evernym.verity.eventing.adapters.kafka.producer

import akka.kafka.ProducerSettings
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.typesafe.config.Config
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}

import scala.jdk.CollectionConverters._

case object ProducerSettingsProvider {

  def apply(config: Config): ProducerSettingsProvider = {
    new ProducerSettingsProvider(config)
  }
}

/**
 * Producer settings should look like this (inherited from `akka.kafka.producer`)
 *
   akka.kafka = {

     # https://github.com/akka/alpakka-kafka/blob/v3.0.0/core/src/main/resources/reference.conf#L6
     producer = {

       # https://github.com/akka/alpakka-kafka/blob/v3.0.0/core/src/main/resources/reference.conf#L41
       kafka-clients = {
         bootstrap.servers = ${verity.kafka.common.bootstrap-servers}
         client.id = ${verity.kafka.common.client.id}
       }

       # override verity specific producer configurations
       resolve-timeout = 3 seconds

       # verity producer adapter configuration
       parallelism = 100
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
   }
 *
 */
final class ProducerSettingsProvider(config: Config) {
  val akkaKafkaConfigReader: ConfigReadHelper = ConfigReadHelper(config.getConfig("akka.kafka").resolve())

  val producerConfig: Config =
    akkaKafkaConfigReader
      .getConfigOption("producer")
      .getOrElse(throw new RuntimeException("required config not found at path: akka.kafka.producer"))

  validateConfig()

  def validateConfig(): Unit = {
    val kafkaClientConfigs = producerConfig
      .getConfig("kafka-clients")
      .entrySet().asScala.map(r => r.getKey -> r.getValue.unwrapped().toString).toMap

    val requiredKafkaClientProperties = Set("bootstrap.servers", "client.id")

    if (!requiredKafkaClientProperties.subsetOf(kafkaClientConfigs.keySet)) {
      throw new RuntimeException("required kafka client properties not found (at path: akka.kafka.producer.kafka-clients): " + requiredKafkaClientProperties.diff(kafkaClientConfigs.keySet).mkString(", "))
    }
    val invalidReqConfigs = kafkaClientConfigs.filter{case (k, v) => v == null || v.isEmpty}
    if (invalidReqConfigs.nonEmpty) {
      throw new RuntimeException("required kafka client properties cannot be empty/null (at path: akka.kafka.producer.kafka-clients): " + invalidReqConfigs.keySet.mkString(", "))
    }
  }

  def kafkaProducerSettings(): ProducerSettings[String, Array[Byte]] = {
    ProducerSettings(producerConfig, new StringSerializer, new ByteArraySerializer)
  }
}