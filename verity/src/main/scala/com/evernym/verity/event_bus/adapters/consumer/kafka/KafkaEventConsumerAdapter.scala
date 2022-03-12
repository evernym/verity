package com.evernym.verity.event_bus.adapters.consumer.kafka

import akka.actor.typed.{ActorSystem => TypedActorSystem}
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.stream.scaladsl.Sink
import com.evernym.verity.event_bus.ports.consumer.Event
import com.evernym.verity.event_bus.ports.consumer.ConsumerPort
import com.evernym.verity.observability.logs.LoggingUtil
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


//one consumer instance can read multiple partitions belonging to several topics
//but one partition would be assigned only to one consumer in the same consumer group
//offsets are always committed for a given consumer group
//  (for example: `verity` consumer group's offset for partition 0 is 8)
object KafkaEventConsumerAdapter
  extends ConsumerPort {

  val logger: Logger = LoggingUtil.getLoggerByClass(getClass)

  def apply(settingsProvider: ConsumerSettingsProvider)
           (implicit executionContext: ExecutionContext,
            actorSystem: TypedActorSystem[_]): Unit = {
    startConsumingFromTopic(settingsProvider)
  }

  private def startConsumingFromTopic(settingsProvider: ConsumerSettingsProvider)
                                     (implicit executionContext: ExecutionContext,
                                      actorSystem: TypedActorSystem[_]) = {
    //TODO: Normal vs Transactional consumers
    Consumer
      //TODO: which variation to use here (plain/committable, partition awareness, subscription type, offset commit consideration etc)
      .committableSource(settingsProvider.kafkaConsumerSettings(), Subscriptions.topics(settingsProvider.topics: _*))
      //TODO: finalize the parallelism (number of Futures that shall run in parallel)
      //TODO: ordered or unordered (mapAsync vs mapAsyncUnordered)?
      .mapAsync(10) { msg =>
        //TODO: is below Try correct way to handle any failures (deserialization, event handling etc) while processing the received event?
        Try {
          val event: Event = null // TODO: create event from the msg
          eventHandler(event)
            .map(_ => msg.committableOffset)
            .recover {
              case e: RuntimeException =>
                logger.error(s"[${msg.record.topic()}:${msg.record.key()} ${msg.record.offset()}] error while handling consumer event : ${e.getMessage}")
                Future.successful(msg.committableOffset)
            }
        } match {
          case Success(result: CommittableOffset) =>
            Future.successful(result)
          case Success(other) =>
            logger.error(s"[${msg.record.topic()}:${msg.record.key()} ${msg.record.offset()}] unexpected result while handling consumer event : ${other.getClass.getSimpleName}")
            Future.successful(msg.committableOffset)    //TODO: confirm this
          case Failure(ex)     =>
            logger.error(s"[${msg.record.topic()}:${msg.record.key()} ${msg.record.offset()}] error while processing consumer event : ${ex.getMessage}")
            Future.successful(msg.committableOffset)    //TODO: confirm this
        }
      }
      //TODO: finalize the `maxBatch`
      .via(Committer.flow(settingsProvider.kafkaCommitterSettings().withMaxBatch(1)))
      .toMat(Sink.seq)(DrainingControl.apply)
      .run()
  }
}
