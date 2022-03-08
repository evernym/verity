package com.evernym.verity.event_bus.adapters.kafka

import akka.actor.typed.{ActorSystem => TypedActorSystem}
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.stream.scaladsl.Sink
import com.evernym.verity.event_bus.ports.ConsumerPort

import scala.concurrent.ExecutionContext


//one consumer instance can read multiple partitions belonging to several topics
//but one partition would be assigned only to one consumer in the same consumer group
object KafkaEventConsumerAdapter
  extends ConsumerPort {

  def apply(settingsProvider: ConsumerSettingsProvider)
           (implicit executionContext: ExecutionContext,
            actorSystem: TypedActorSystem[_]): Unit = {
    startConsumingFromTopic(settingsProvider)
  }

  private def startConsumingFromTopic(settingsProvider: ConsumerSettingsProvider)
                                     (implicit executionContext: ExecutionContext,
                                      actorSystem: TypedActorSystem[_]) = {
    Consumer
      //TODO: which variation to use here (plain/committable, partition awareness, subscription type, offset commit consideration etc)
      .committableSource(settingsProvider.kafkaConsumerSettings(), Subscriptions.topics(settingsProvider.topics: _*))
      //TODO: finalize the parallelism
      .mapAsync(10) { msg =>
        val event = null // TODO: create event from the msg
        eventHandler(event).map(_ => msg.committableOffset)
      }
      //TODO: finalize the `maxBatch`
      .via(Committer.flow(settingsProvider.kafkaCommitterSettings().withMaxBatch(1)))
      .toMat(Sink.seq)(DrainingControl.apply)
      .run()
  }
}
