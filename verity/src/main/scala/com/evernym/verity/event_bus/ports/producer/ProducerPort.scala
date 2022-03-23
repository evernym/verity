package com.evernym.verity.event_bus.ports.producer

import akka.Done

import scala.concurrent.Future

trait ProducerPort {
  def send(topic: String, payload: Array[Byte]): Future[Done]
}