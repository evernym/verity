package com.evernym.verity.integration.base

import akka.Done
import com.evernym.verity.config.ConfigConstants.VDR_UNQUALIFIED_LEDGER_PREFIX
import com.evernym.verity.eventing.event_handlers.EndorsementMessageHandler._
import com.evernym.verity.eventing.event_handlers.{CLOUD_EVENT_TYPE, DATA_FIELD_LEDGER_PREFIX, DATA_FIELD_REQUEST_SOURCE, EVENT_ENDORSEMENT_COMPLETE_V1, EVENT_ENDORSEMENT_REQ_V1, TOPIC_REQUEST_ENDORSEMENT, TOPIC_SSI_ENDORSEMENT}
import com.evernym.verity.eventing.ports.consumer.{Message, MessageHandler}
import com.evernym.verity.eventing.ports.producer.ProducerPort
import com.evernym.verity.protocol.engine.asyncapi.endorser.ENDORSEMENT_RESULT_SUCCESS_CODE
import com.typesafe.config.Config
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.core.provider.EventFormatProvider
import io.cloudevents.jackson.JsonFormat
import org.json.JSONObject

import java.net.URI
import java.time.OffsetDateTime.now
import java.time.ZoneId
import java.util.UUID
import scala.concurrent.Future

class EndorsementReqMsgHandler(config: Config, eventProducer: ProducerPort) extends MessageHandler {

  lazy val indyLedgerPrefix: String = config.getString(VDR_UNQUALIFIED_LEDGER_PREFIX)

  override def handleMessage(message: Message): Future[Done] = {
    message.metadata.topic match {
      case TOPIC_REQUEST_ENDORSEMENT =>
        val jsonObject = message.cloudEvent
        jsonObject.get(CLOUD_EVENT_TYPE) match {
          case EVENT_ENDORSEMENT_REQ_V1 => processEndorsementReq(jsonObject)
          case other => Future.successful(Done)
        }
      case other => Future.successful(Done)
    }
  }

  private def processEndorsementReq(receivedEvent: JSONObject): Future[Done] = {

    val respJsonObject = new JSONObject()
    respJsonObject.put(DATA_FIELD_ENDORSEMENT_ID, UUID.randomUUID().toString)
    respJsonObject.put(DATA_FIELD_LEDGER_PREFIX, indyLedgerPrefix)
    respJsonObject.put(DATA_FIELD_REQUEST_SOURCE, receivedEvent.getString("source"))
    respJsonObject.put(DATA_FIELD_SUBMITTER_DID, "submitterdid")
    val resultJSONObject = new JSONObject()
    resultJSONObject.put(DATA_FIELD_RESULT_CODE, ENDORSEMENT_RESULT_SUCCESS_CODE)
    resultJSONObject.put(DATA_FIELD_RESULT_DESCR, "Transaction Written to VDR (normally a ledger)")
    respJsonObject.put(DATA_FIELD_RESULT, resultJSONObject)

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
