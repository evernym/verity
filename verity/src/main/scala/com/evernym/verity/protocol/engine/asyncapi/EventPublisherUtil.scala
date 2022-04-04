package com.evernym.verity.protocol.engine.asyncapi

import akka.Done
import com.evernym.verity.event_bus.event_handlers.RequestSourceUtil
import com.evernym.verity.event_bus.ports.producer.ProducerPort
import com.evernym.verity.protocol.engine.{PinstId, ProtoRef, ThreadId}
import com.evernym.verity.util2.RouteId
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.core.provider.EventFormatProvider
import io.cloudevents.jackson.JsonFormat

import java.net.URI
import java.time.OffsetDateTime.now
import java.time.ZoneId
import java.util.UUID
import scala.concurrent.Future


class EventPublisherUtil(routingContext: RoutingContext,
                         producerPort: ProducerPort) {

  def publishToEventBus(payload: String, eventType: String, topic: String): Future[Done] = {
    //TODO: Do we need to use `extension` in the cloud event?
    val event: CloudEvent = CloudEventBuilder.v1()
      .withId(UUID.randomUUID().toString)
      .withType(eventType)
      .withSource(URI.create(cloudEventSource))
      .withData("application/json", payload.getBytes())
      .withTime(now(ZoneId.of("UTC")))
      .build()

    val data = EventFormatProvider
      .getInstance
      .resolveFormat(JsonFormat.CONTENT_TYPE)
      .serialize(event)

    producerPort.send(topic, data)
  }

  private lazy val cloudEventSource = RequestSourceUtil.build(
    "https://verity.avast.com",   //TODO: is this value ok and/or shall be configurable?
    routingContext.routeId,
    routingContext.protoRef,
    routingContext.pinstId,
    routingContext.threadId
  )
}

/**
 * this is used to construct `source` used during publishing event
 * @param routeId
 * @param protoRef
 * @param pinstId
 * @param threadId
 */
case class RoutingContext(routeId: RouteId, protoRef: ProtoRef, pinstId: PinstId, threadId: ThreadId)