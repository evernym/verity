package com.evernym.verity.event_bus.adapters.basic

import akka.Done
import akka.actor.{ActorRef, Props}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.base.CoreActor
import com.evernym.verity.event_bus.adapters.basic.consumer.ConsumedEvent

object BasicTopic {
  def props(topicName: String): Props = Props(new BasicTopic(topicName))
}

//sharded non persistent topic actor (one for each topic)
class BasicTopic(topicName: String)
  extends CoreActor {

  override def receiveCmd: Receive = {
    case PushEvent(event) =>
      println("### event: " + event)
      process(event)
      sender() ! Done

    case RegisterConsumer(ar) =>
      consumers = consumers:+ ar
      sender() ! Done
  }

  var consumers: List[ActorRef] = List.empty

  private def process(event: Array[Byte]): Unit = {
    consumers.foreach(_ ! ConsumedEvent(topicName, event))
  }

}

case class PushEvent(cloudEvent: Array[Byte]) extends ActorMessage
case class RegisterConsumer(actorRef: ActorRef) extends ActorMessage