package com.evernym.verity.event_bus.event_handlers

import akka.Done
import akka.actor.ActorRef
import com.evernym.verity.event_bus.ports.consumer.Event

import scala.concurrent.Future

//responsible to handle various events related to 'endorser-registry' (active/inactive etc)
//prepare appropriate command/message from the event and send it over to the appropriate actor

class EndorserRegistryEventHandler(singletonParentProxy: ActorRef) {
  def handleEvent(event: Event): Future[Done] = {
    Future.successful(Done)
  }
}
