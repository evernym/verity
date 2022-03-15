package com.evernym.verity.event_bus.ports.consumer

import akka.Done

import java.time.Instant
import scala.concurrent.Future


/**
 * interface to be able to handle consumed events (from multiple topics?)
 */
trait ConsumerPort {

  /**
   * start the consumer
   */
  def start(): Unit

  /**
   * stop consumer
   * @return
   */
  def stop(): Future[Done]

  /**
   * an event handler which implements `EventHandler` interface
   * @return
   */
  def eventHandler: EventHandler
}

trait EventHandler {

  /**
   * handle given/parsed events (like sending those events to appropriate actors etc)
   * @param event
   * @return
   */
  def handleEvent(event: Event): Future[Done]
}

/**
 * parsed event which contains metadata and the actual published message
 *
 * @param metadata event metadata
 * @param message event message (actual message submitted by publisher)
 */
case class Event(metadata: Metadata, message: Message)

/**
 *
 * @param topic topic name
 * @param partition partition id
 * @param offset position of the record in the partition
 * @param timestamp record timestamp (creation time)
 */
case class Metadata(topic: String, partition: Int, offset: Long, timestamp: Instant)