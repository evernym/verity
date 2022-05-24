package com.evernym.verity.eventing.event_handlers

import com.evernym.verity.testkit.BasicSpec
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.cloudevents.CloudEvent
import io.cloudevents.core.CloudEventUtils.mapData
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.core.provider.EventFormatProvider
import io.cloudevents.jackson.{JsonFormat, PojoCloudEventDataMapper}

import java.net.URI
import java.time.OffsetDateTime.now
import java.time.ZoneId


class CloudEventSpec
  extends BasicSpec {

  val jacksonMapper = new ObjectMapper()
  jacksonMapper.registerModule(DefaultScalaModule)

  val event: CloudEvent = CloudEventBuilder.v1()
    .withId("000")
    .withType("example.event.type")
    .withSource(URI.create("http://example.com"))
    .withData("application/json", "{\"key\":\"value\"}".getBytes())
    .withTime(now(ZoneId.of("UTC")))
    .withExtension("evernym", 112)
    .build()

  "Cloud Event" - {
    "should be able to build event with builder" in {
      event.getId shouldBe "000"
    }

    "should be able to serialize to JSON" in {
      val serialized = EventFormatProvider
        .getInstance
        .resolveFormat(JsonFormat.CONTENT_TYPE)
        .serialize(event)

      val tree = jacksonMapper.readTree(new String(serialized))
      tree.get("id").asText() shouldBe "000"
    }

    "should handle CaseClass deserialization" in {
      val dataObj = mapData(
        event,
        PojoCloudEventDataMapper.from(jacksonMapper, classOf[SimpleCaseClass])
      ).getValue

      dataObj shouldBe SimpleCaseClass(key = "value")
    }
  }

}

case class SimpleCaseClass(key: String)