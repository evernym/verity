package com.evernym.verity.event_bus.adapters.kafka

import akka.actor.ActorSystem
import akka.kafka.{CommitterSettings, ConsumerSettings}
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._


case object ConsumerSettingsProvider {

  def apply(system: ActorSystem): ConsumerSettingsProvider = {
    new ConsumerSettingsProvider(system.settings.config, system)
  }
}

/**
 * Consumer settings should look like this (inherited from `akka.kafka.consumer`)
   {
      service-name = "kafkaService"
      service-name = ${?KAFKA_SERVICE_NAME}

      topics = ["endorsement"]
      topics = ${?KAFKA_CONSUMER_TOPICS}

      group-id = "verity"
      group-id = ${?KAFKA_CONSUMER_GROUP_ID}

   }
 *
 */
final class ConsumerSettingsProvider(config: Config, system: ActorSystem) {

  val bootstrapServers = config.getString("verity.kafka.consumer.service-name")
  val topics = config.getStringList("verity.kafka.consumer.topics").asScala.toList
  val groupId = config.getString("verity.kafka.consumer.group-id")

  def kafkaConsumerSettings(): ConsumerSettings[String, String] = {
    //TODO: finalize the serializers (string, bytearray etc)
    ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(groupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withStopTimeout(0.seconds)
  }

  def kafkaCommitterSettings(): CommitterSettings = {
    //TODO: finalize this
    //will read default configs from `akka.kafka.committer`
    CommitterSettings(system)
  }
}