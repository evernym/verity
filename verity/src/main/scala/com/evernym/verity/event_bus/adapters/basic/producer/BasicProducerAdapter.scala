package com.evernym.verity.event_bus.adapters.basic.producer

import akka.Done
import akka.actor.ActorSystem
import com.evernym.verity.actor.agent.EventProducerAdapterBuilder
import com.evernym.verity.config.AppConfig
import com.evernym.verity.event_bus.adapters.basic.{BaseEventAdapter, PushEvent}
import com.evernym.verity.event_bus.ports.producer.ProducerPort

import scala.concurrent.{ExecutionContext, Future}


class BasicProducerAdapterBuilder
  extends EventProducerAdapterBuilder {

  override def build(appConfig: AppConfig,
                     executionContext: ExecutionContext,
                     actorSystem: ActorSystem): ProducerPort = {
    new BasicProducerAdapter(appConfig)(actorSystem)
  }
}

class BasicProducerAdapter(val appConfig: AppConfig)(implicit actorSystem: ActorSystem)
  extends ProducerPort
    with BaseEventAdapter {

  override def send(topicName: String, payload: Array[Byte]): Future[Done] = {
    sendToTopicActor(topicName, PushEvent(payload))
  }
}