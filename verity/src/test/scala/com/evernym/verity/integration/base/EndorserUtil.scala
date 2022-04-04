package com.evernym.verity.integration.base

import akka.Done
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.did.DidStr
import com.evernym.verity.event_bus.event_handlers.{EVENT_ENDORSER_ACTIVATED_V1, TOPIC_SSI_ENDORSER}
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

object EndorserUtil {

  val endorserDid: DidStr = CommonSpecUtil.generateNewDid().did

  def registerActiveEndorser(endorserDid: DidStr, eventProducer: ProducerPort): Future[Done] = {
    val jsonObject = new JSONObject()
    jsonObject.put("endorserdid", endorserDid)
    jsonObject.put("ledgerprefix", INDY_LEDGER_PREFIX)

    val event = CloudEventBuilder.v1()
      .withId(UUID.randomUUID().toString)
      .withType(EVENT_ENDORSER_ACTIVATED_V1)
      .withSource(URI.create("event-source://v1:ssi:endorser"))
      .withData("application/json", jsonObject.toString().getBytes())
      .withTime(now(ZoneId.of("UTC")))
      .build()

    val payload = EventFormatProvider
      .getInstance
      .resolveFormat(JsonFormat.CONTENT_TYPE)
      .serialize(event)

    eventProducer.send(TOPIC_SSI_ENDORSER, payload)
  }
}
