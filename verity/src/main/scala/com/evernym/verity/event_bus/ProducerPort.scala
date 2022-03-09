package com.evernym.verity.event_bus

import scala.concurrent.Future

trait ProducerPort {
  def submitEndorseTxn(payload: String, context: String, topic: String): Future[Unit]
}
