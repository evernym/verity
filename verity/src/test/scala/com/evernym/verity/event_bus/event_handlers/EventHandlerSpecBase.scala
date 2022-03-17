package com.evernym.verity.event_bus.event_handlers

import akka.util.Timeout
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.ExecutionContextProvider
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.core.provider.EventFormatProvider
import io.cloudevents.jackson.JsonFormat
import org.json.JSONObject
import org.scalatest.BeforeAndAfterAll

import java.net.URI
import java.time.{OffsetDateTime, ZoneId}
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


trait EventHandlerSpecBase
  extends PersistentActorSpec
    with BasicSpec
    with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    super.beforeAll()
    platform
  }

  protected def createCloudEvent(typ: String, source: String, data: String): CloudEvent = {
    CloudEventBuilder
      .v1()
      .withId(UUID.randomUUID().toString)
      .withType(typ)
      .withSource(URI.create(source))
      .withData("application/json", data.getBytes())
      .withTime(OffsetDateTime.now(ZoneId.of("UTC")))
      .withExtension("company", 1)
      .build()
  }

  protected def toJsonObject(event: CloudEvent): JSONObject = {
    new JSONObject(new String(serializedCloudEvent(event)))
  }

  protected def serializedCloudEvent(event: CloudEvent): Array[Byte] = {
    EventFormatProvider
      .getInstance
      .resolveFormat(JsonFormat.CONTENT_TYPE)
      .serialize(event)
  }

  implicit val timeout: Timeout = Timeout(10.seconds)
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  implicit val executionContext: ExecutionContext = ecp.futureExecutionContext
  override def executionContextProvider: ExecutionContextProvider = ecp
}
