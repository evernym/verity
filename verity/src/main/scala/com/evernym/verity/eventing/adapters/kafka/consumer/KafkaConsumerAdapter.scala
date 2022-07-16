package com.evernym.verity.eventing.adapters.kafka.consumer

import akka.Done
import akka.actor.typed.{ActorSystem => TypedActorSystem}
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.stream.scaladsl.Sink
import com.evernym.verity.eventing.ports.consumer.{ConsumerPort, Message, MessageHandler, Metadata}
import com.evernym.verity.observability.logs.LoggingUtil
import com.typesafe.scalalogging.Logger
import org.json.JSONObject

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


object KafkaConsumerAdapter {
  def apply(eventProcessor: MessageHandler,
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
class KafkaConsumerAdapter(override val messageHandler: MessageHandler,
                           settingsProvider: ConsumerSettingsProvider)
                          (implicit executionContext: ExecutionContext,
                           actorSystem: TypedActorSystem[_])
  extends ConsumerPort {

  private val logger: Logger = LoggingUtil.getLoggerByClass(getClass)

  private var controller: Option[DrainingControl[_]] = None

  override def start(): Future[Done] = {
    logger.info("kafka consumer is about to start...")
    controller = Option(
      Consumer
        .committableSource(settingsProvider.kafkaConsumerSettings(), Subscriptions.topics(settingsProvider.topics: _*))
        //because we want to commit the offset, it makes sense to use `mapAsync` instead of `mapAsyncUnordered`
        // otherwise the last offset which gets committed may not be the desired one (because futures can complete in any order)
        .mapAsync(settingsProvider.msgHandlingParallelism) { committableMsg =>   //how many futures in parallel to process each received message
          val logMsgPrefix = s"[${committableMsg.record.topic()}/${committableMsg.record.partition()}/${committableMsg.record.offset()}]"
          Try {
            logger.info(s"$logMsgPrefix: committable message received: $committableMsg")
            val createTime = Instant.ofEpochMilli(committableMsg.record.timestamp())
            val metadata = Metadata(committableMsg.record.topic(), committableMsg.record.partition(), committableMsg.record.offset(), createTime)
            val cloudEvent = new JSONObject(new String(committableMsg.record.value()))
            val message = Message(metadata, cloudEvent)
            logger.info(s"$logMsgPrefix: committable message parsed successfully")
            messageHandler
              .handleMessage(message)
              .map { _ =>
                logger.info(s"$logMsgPrefix: event processed successfully")
                committableMsg.committableOffset
              }.recover {
                case e: Throwable =>
                  logger.error(s"$logMsgPrefix: error while handling consumed event: ${e.getMessage}")
                  committableMsg.committableOffset
              }
          } match {
            case Success(result: Future[CommittableOffset]) =>
              logger.info(s"$logMsgPrefix: event handled successfully")
              result
            case Failure(ex)     =>
              logger.error(s"$logMsgPrefix: error while parsing/processing consumed event: ${ex.getMessage}")
              Future.successful(committableMsg.committableOffset)
          }
        }
        .via(Committer.flow(settingsProvider.kafkaCommitterSettings()))
        .toMat(Sink.seq)(DrainingControl.apply)
        .run()
    )
    logger.info("kafka consumer is started.")
    Future.successful(Done)
  }

  override def stop(): Future[Done] = {
    logger.info("kafka consumer is about to be stopped.")
    controller.map(_.drainAndShutdown())
    controller.map(_.isShutdown).getOrElse(Future.successful(Done))
  }
}
