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
 *
   verity.kafka.consumer {
      service-name = "kafkaService"
      service-name = ${?KAFKA_SERVICE_NAME}

      group-id = "verity"
      group-id = ${?KAFKA_CONSUMER_GROUP_ID}

      topics = ["endorsement"]
      topics = ${?KAFKA_CONSUMER_TOPICS}
   }
 *
 */
final class ConsumerSettingsProvider(config: Config, system: ActorSystem) {

  val bootstrapServers: String = config.getString("verity.kafka.consumer.service-name")
  val groupId: String = config.getString("verity.kafka.consumer.group-id")
  val topics: Seq[String] = config.getStringList("verity.kafka.consumer.topics").asScala

  def kafkaConsumerSettings(): ConsumerSettings[String, String] = {
    //TODO: finalize the serializers (string, bytearray etc)
    ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(groupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")    //see related 'AUTO_OFFSET_RESET_DOC' for it's detail
      .withStopTimeout(0.seconds)
  }

  def kafkaCommitterSettings(): CommitterSettings = {
    //TODO: finalize this
    //will read default configs from `akka.kafka.committer`
    CommitterSettings(system)
  }
}