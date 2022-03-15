package com.evernym.verity.event_bus.event_handlers

import com.evernym.verity.event_bus.ports.consumer.{Event, EventHandler}

import scala.concurrent.Future

class ConsumedEventHandler
  extends EventHandler {

  //TODO: to be implemented
  override def handleEvent(event: Event): Future[Unit] = ???
}
