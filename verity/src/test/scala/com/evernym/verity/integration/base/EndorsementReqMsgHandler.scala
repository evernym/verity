package com.evernym.verity.integration.base

import akka.Done
import com.evernym.verity.event_bus.event_handlers.{EVENT_ENDORSEMENT_COMPLETE_V1, EVENT_ENDORSEMENT_REQ_V1, TOPIC_SSI_ENDORSEMENT, TOPIC_SSI_ENDORSEMENT_REQ}
import com.evernym.verity.event_bus.ports.consumer.{Message, MessageHandler}
import com.evernym.verity.event_bus.ports.producer.ProducerPort
import com.evernym.verity.protocol.engine.asyncapi.endorser.INDY_LEDGER_PREFIX
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.core.provider.EventFormatProvider
import io.cloudevents.jackson.JsonFormat
import org.json.JSONObject

import java.net.URI
import java.time.OffsetDateTime.now
import java.time.ZoneId
import java.util.UUID
import scala.concurrent.Future

class EndorsementReqMsgHandler(eventProducer: ProducerPort) extends MessageHandler {

  override def handleMessage(message: Message): Future[Done] = {
    message.metadata.topic match {
      case TOPIC_SSI_ENDORSEMENT_REQ =>
        val jsonObject = message.cloudEvent
        jsonObject.get("type") match {
          case EVENT_ENDORSEMENT_REQ_V1 => processEndorsementReq(jsonObject)
          case other => Future.successful(Done)
        }
      case other => Future.successful(Done)
    }
  }

  private def processEndorsementReq(receivedEvent: JSONObject): Future[Done] = {

    val respJsonObject = new JSONObject()
    respJsonObject.put("endorsementid", UUID.randomUUID().toString)
    respJsonObject.put("ledgerprefix", INDY_LEDGER_PREFIX)
    respJsonObject.put("requestsource", receivedEvent.getString("source"))
    respJsonObject.put("submitterdid", "submitterdid")
    val resultJSONObject = new JSONObject()
    resultJSONObject.put("code", "ENDT-RES-0001")
    resultJSONObject.put("descr", "Transaction Written to VDR (normally a ledger)")
    respJsonObject.put("result", resultJSONObject)

    val event = CloudEventBuilder.v1()
      .withId(UUID.randomUUID().toString)
      .withType(EVENT_ENDORSEMENT_COMPLETE_V1)
      .withSource(URI.create("event-source://v1:ssi:endorser"))
      .withData("application/json", respJsonObject.toString().getBytes())
      .withTime(now(ZoneId.of("UTC")))
      .build()

    val payload = EventFormatProvider
      .getInstance
      .resolveFormat(JsonFormat.CONTENT_TYPE)
      .serialize(event)

    eventProducer.send(TOPIC_SSI_ENDORSEMENT, payload)
  }

}
