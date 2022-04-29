package com.evernym.verity.protocol.engine.asyncapi

import akka.Done
import com.evernym.verity.eventing.event_handlers.RequestSourceUtil
import com.evernym.verity.eventing.ports.producer.ProducerPort
import com.evernym.verity.protocol.engine.{DomainId, PinstId, ProtoRef, RelationshipId, ThreadId}
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
    routingContext.domainId,
    routingContext.relationshipId,
    routingContext.pinstId,
    routingContext.threadId,
    routingContext.protoRef
  )
}

/**
 * this is used to construct `source` used during publishing event
 * @param domainId
 * @param relationshipId
 * @param pinstId
 * @param threadId
 * @param protoRef
 */
case class RoutingContext(domainId: DomainId, relationshipId: RelationshipId, pinstId: PinstId, threadId: ThreadId, protoRef: ProtoRef)