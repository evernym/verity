package com.evernym.verity.event_bus.adapters.basic.event_store

import akka.Done
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.StatusCodes.GatewayTimeout
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger

import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.fromExecutor
import scala.concurrent.duration._


//non persistent (in-memory) topic message store, delivers the published/received events to subscribed consumers)
// this is just a basic version to make verity working in non production environment
// this implementation can be enhanced to make this more robust, reliable etc.
class TopicMsgStore
  extends CoreActorExtended {

  implicit val actorSystem: ActorSystem = context.system
  implicit val ec: ExecutionContext = fromExecutor(context.dispatcher)

  override def receiveCmd: Receive = {
    case AddMessage(msg) =>
      msgQueue = msgQueue.enqueue(msg)
      sender() ! Done
      deliver()

    case AddSubscriber(subscriber) =>
      val (_, unmatched) = subscribers.partition(_.id == subscriber.id)
      subscribers = unmatched :+ subscriber
      sender() ! Done
      deliver()

    case PeriodicJob => deliver()
  }

  private def deliver(): Unit = {
    if (subscribers.nonEmpty) {
      msgQueue.dequeueOption.foreach { case (msg, pendingMsgs) =>
        subscribers.foreach { subscriber =>
          sendToSubscriber(msg, subscriber.webhook)
        }
        msgQueue = pendingMsgs
      }
    }
  }

  private def sendToSubscriber(msg: Array[Byte], webhook: String): Unit = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = webhook,
      entity = HttpEntity(ContentTypes.`application/octet-stream`, msg)
    )
    Http().singleRequest(request).recover {
      case e =>
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        val errMsg = s"connection not established with remote server: ${request.uri}"
        logger.error(errMsg)
        HttpResponse(StatusCodes.custom(GatewayTimeout.intValue, errMsg, errMsg))
    }
  }

  scheduleJob("periodic_job", scheduledJobInterval.toSeconds.toInt, PeriodicJob)

  lazy val configReadHelper = new ConfigReadHelper(actorSystem.settings.config)

  lazy val scheduledJobInterval: Duration = {
    configReadHelper.getDurationOption("verity.event-bus.basic.store.periodic_job.interval")
      .getOrElse(5.seconds)
  }

  lazy val logger: Logger = getLoggerByClass(getClass)
  var msgQueue: Queue[Array[Byte]] = Queue.empty
  var subscribers: List[Subscriber] = List.empty
}

case class AddMessage(msg: Array[Byte]) extends ActorMessage
case class AddSubscriber(subscriber: Subscriber) extends ActorMessage
case object PeriodicJob extends ActorMessage

object TopicMsgStore {
  def props(): Props = Props(new TopicMsgStore)
}