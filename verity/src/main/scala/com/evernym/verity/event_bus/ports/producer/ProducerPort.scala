package com.evernym.verity.event_bus.ports.producer

import scala.concurrent.Future

trait ProducerPort {
  //TODO: the payload type would be String or Array[Byte]?
  def send(payload: String, topic: String): Future[Unit]
}