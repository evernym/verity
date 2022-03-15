package com.evernym.verity.event_bus.event_handlers

import akka.Done
import akka.actor.ActorRef
import com.evernym.verity.event_bus.ports.consumer.{Event, EventHandler}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

//all consumed event should come to this event handler and then it will decide what to do with it
class ConsumedEventHandler(singletonParentProxy: ActorRef)
  extends EventHandler {

  val logger: Logger = getLoggerByClass(getClass)

  lazy val endorserRegistryEventHandler = new EndorserRegistryEventHandler(singletonParentProxy)

  override def handleEvent(event: Event): Future[Done] = {
    event.metadata.topic match {
      case TOPIC_ENDORSER_REGISTRY =>
        endorserRegistryEventHandler.handleEvent(event)

      case _ =>
        logger.error(s"unhandled consumed event" + event.metadata)
        Future.successful(Done)
    }
  }
}
