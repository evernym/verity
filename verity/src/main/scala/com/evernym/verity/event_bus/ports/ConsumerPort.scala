package com.evernym.verity.event_bus.ports

import scala.concurrent.Future


/**
 * interface to be able to handle consumed events (from multiple topics?)
 */
trait ConsumerPort {

  /**
   * event handler to run business logic for each received event
   * based on the metadata (topic name etc), the handler needs to be able to different types of events
   * from various topics/sources (endorsement events etc)
   *
   * @param event consumed/received event
   */
  def eventHandler(event: Event): Future[Unit] = {
    //based on event information, it has to be converted to appropriate command and sent to corresponding actors for further handling
    ???
  }
}
