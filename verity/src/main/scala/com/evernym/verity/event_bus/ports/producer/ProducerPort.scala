package com.evernym.verity.event_bus.ports.producer

import scala.concurrent.Future

trait ProducerPort {
  def submitEndorseTxn(payload: String, context: String, topic: String): Future[Unit]
}
