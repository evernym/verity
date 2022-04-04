package com.evernym.verity.event_bus.adapters.basic.producer

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import com.evernym.verity.actor.agent.EventProducerAdapterBuilder
import com.evernym.verity.config.AppConfig
import com.evernym.verity.event_bus.adapters.basic.BaseEventAdapter
import com.evernym.verity.event_bus.ports.producer.ProducerPort

import scala.concurrent.{ExecutionContext, Future}


class BasicProducerAdapterBuilder
  extends EventProducerAdapterBuilder {

  override def build(appConfig: AppConfig,
                     executionContext: ExecutionContext,
                     actorSystem: ActorSystem): ProducerPort = {
    new BasicProducerAdapter(appConfig)(actorSystem, executionContext)
  }
}

class BasicProducerAdapter(val appConfig: AppConfig)
                          (implicit actorSystem: ActorSystem, executionContext: ExecutionContext)
  extends ProducerPort
    with BaseEventAdapter {

  override def send(topicName: String, payload: Array[Byte]): Future[Done] = {
    publishToTopic(topicName, payload)
      .map(_ => Done)
  }

  private def publishToTopic(topicName: String, payload: Array[Byte]): Future[HttpResponse] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = eventStoreParam.url + s"/event-store/topic/$topicName/publish",
      entity = HttpEntity(payload)
    )
    postHttpRequest(request)
  }
}