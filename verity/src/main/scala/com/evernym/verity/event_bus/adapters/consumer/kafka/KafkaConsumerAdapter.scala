package com.evernym.verity.event_bus.adapters.consumer.kafka

import akka.Done
import akka.actor.typed.{ActorSystem => TypedActorSystem}
import akka.kafka.ConsumerMessage.{CommittableMessage, CommittableOffset}
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.stream.scaladsl.Sink
import com.evernym.verity.event_bus.ports.consumer.{ConsumerPort, Event, EventHandler, Message, Metadata}
import com.evernym.verity.observability.logs.LoggingUtil
import com.typesafe.scalalogging.Logger

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


object KafkaConsumerAdapter {
  def apply(eventProcessor: EventHandler,
            settingsProvider: ConsumerSettingsProvider)
           (implicit executionContext: ExecutionContext,
            actorSystem: TypedActorSystem[_]): Unit = {
    new KafkaConsumerAdapter(eventProcessor, settingsProvider)
  }
}

//one consumer instance can read multiple partitions belonging to same and/or different topics
//but one partition would be assigned only to "one consumer in the same consumer group"
//offsets are always committed for a given consumer group (not for the consumer instance)
//  (for example: `verity` consumer group's offset for partition 0 is 8)
class KafkaConsumerAdapter(override val eventHandler: EventHandler,
                           settingsProvider: ConsumerSettingsProvider)
                          (implicit executionContext: ExecutionContext,
                           actorSystem: TypedActorSystem[_])
  extends ConsumerPort {

  val logger: Logger = LoggingUtil.getLoggerByClass(getClass)

  var controller: Option[DrainingControl[_]] = None

  override def start(): Unit = {
    controller = Option(
      Consumer
      .committableSource(settingsProvider.kafkaConsumerSettings(), Subscriptions.topics(settingsProvider.topics: _*))
      //because we want to commit the offset I think, it makes sense to use `mapAsync` instead of `mapAsyncUnordered`
        // otherwise the last offset which gets committed may be not the desired one (because futures can complete in any order)
      .mapAsync(settingsProvider.msgHandlingParallelism) { committableMsg =>   //how many futures in parallel to process each received message
        Try {
          logger.debug(prepareLogMsgStr(committableMsg, s"committable message received: $committableMsg"))
          val message = Message.parseFrom(committableMsg.record.value().getBytes())
          val createTime = Instant.ofEpochMilli(committableMsg.record.timestamp())
          val metadata = Metadata(committableMsg.record.topic(), committableMsg.record.partition(), committableMsg.record.offset(), createTime)
          val event = Event(metadata, message)
          logger.debug(prepareLogMsgStr(committableMsg, s"committable message parsed successfully"))
          eventHandler
            .handleEvent(event)
            .map { _ =>
              logger.debug(prepareLogMsgStr(committableMsg, s"event handled successfully"))
              committableMsg.committableOffset
            }.recover {
              case e: RuntimeException =>
                logger.error(prepareLogMsgStr(committableMsg, s"error while handling consumer event: ${e.getMessage}"))
                committableMsg.committableOffset
            }
        } match {
          case Success(result: Future[CommittableOffset]) => result
          case Failure(ex)     =>
            logger.error(prepareLogMsgStr(committableMsg,s"error while parsing/processing consumer event: ${ex.getMessage}"))
            Future.successful(committableMsg.committableOffset)
        }
      }
      .via(Committer.flow(settingsProvider.kafkaCommitterSettings()))
      .toMat(Sink.seq)(DrainingControl.apply)
      .run()
    )
  }

  override def stop(): Future[Done] = {
    controller.map(_.drainAndShutdown())
    controller.map(_.isShutdown).getOrElse(Future.successful(Done))
  }

  private def prepareLogMsgStr(msg: CommittableMessage[String, String], str: String): String = {
    s"[${msg.record.topic()}${Option(msg.record.key()).map(k => s":$k").getOrElse("")}:${msg.record.offset()}] $str"
  }
}
