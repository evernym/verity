package com.evernym.verity.event_bus.ports.consumer

import akka.Done
import org.json.JSONObject

import java.time.Instant
import scala.concurrent.Future


/**
 * interface to be able to handle consumed messages (from multiple topics?)
 */
trait ConsumerPort {

  /**
   * start the consumer
   */
  def start(): Future[Done]

  /**
   * stop consumer
   * @return
   */
  def stop(): Future[Done]

  /**
   * an event handler which implements `EventHandler` interface
   * @return
   */
  def messageHandler: MessageHandler
}

trait MessageHandler {

  /**
   * handle received/consumed message (like sending it to appropriate actors etc)
   *
   * @param message
   * @return
   */
  def handleMessage(message: Message): Future[Done]
}

/**
 * parsed message which contains metadata and the actual published cloud event
 *
 * @param metadata event metadata
 * @param cloudEvent event message (actual message submitted by publisher)
 */
case class Message(metadata: Metadata, cloudEvent: JSONObject)

/**
 *
 * @param topic topic name
 * @param partition partition id
 * @param offset position of the record in the partition
 * @param timestamp record timestamp (creation time)
 */
case class Metadata(topic: String, partition: Int, offset: Long, timestamp: Instant)