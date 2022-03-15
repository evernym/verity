package com.evernym.verity.event_bus.event_handlers

import akka.actor.ActorRef
import akka.pattern.extended.ask
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import com.evernym.verity.actor.cluster_singleton.ForEndorserRegistry
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.endorser_registry.EndorserRegistry.Commands.GetEndorsers
import com.evernym.verity.endorser_registry.EndorserRegistry.Replies.LedgerEndorsers
import com.evernym.verity.endorser_registry.States.Endorser
import com.evernym.verity.event_bus.ports.consumer.{Message, Metadata}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.ExecutionContextProvider
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.core.provider.EventFormatProvider
import io.cloudevents.jackson.JsonFormat
import org.json.JSONObject

import java.net.URI
import java.time.{Instant, OffsetDateTime, ZoneId}
import java.util.UUID
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._


class EndorserRegistryMessageHandlerSpec
  extends PersistentActorSpec
    with BasicSpec {

  "EndorserRegistryEventHandler" - {
    "when received AddEndorser cloud event message" - {
      "should handle it successfully" in {
        val fut = endorserRegistryEventHandler.handleMessage(
          Message(
            Metadata(TOPIC_ENDORSER_REGISTRY, partition = 1, offset = 0, Instant.now()),
            toJsonObject(createCloudEvent(TYPE_ENDORSER_ACTIVE, "pinsti1", """{"ledger":"ledger1", "did":"did1", "verKey": "verKey1"}"""))
          )
        )
        Await.result(fut, 500.seconds)
      }
    }
  }

  "EndorserRegistryEventHandler" - {
    "when received another AddEndorser cloud event message" - {
      "should handle it successfully" in {
        val fut = endorserRegistryEventHandler.handleMessage(
          Message(
            Metadata(TOPIC_ENDORSER_REGISTRY, partition = 1, offset = 0, Instant.now()),
            toJsonObject(createCloudEvent(TYPE_ENDORSER_ACTIVE, "111", """{"ledger":"ledger1", "did":"did2", "verKey": "verKey2"}"""))
          )
        )
        Await.result(fut, 500.seconds)
      }
    }
  }

  "SingletonProxy" - {
    "when asked for ledger endorsers (post addition)" - {
      "should return appropriate endorser" in {
        val fut = singletonParentProxy
          .ask{ ref: ActorRef => ForEndorserRegistry(GetEndorsers("ledger1", ref))}
          .mapTo[LedgerEndorsers]
        val ledgerEndorsers = Await.result(fut, 500.seconds)
        ledgerEndorsers.endorsers shouldBe List(Endorser("did1", "verKey1"), Endorser("did2", "verKey2"))
        ledgerEndorsers.latestEndorser shouldBe Option(Endorser("did2", "verKey2"))
      }
    }
  }

  "EndorserRegistryEventHandler" - {
    "when received RemoveEndorser cloud event message" - {
      "should handle it successfully" in {
        val fut = endorserRegistryEventHandler.handleMessage(
          Message(
            Metadata(TOPIC_ENDORSER_REGISTRY, partition = 1, offset = 1, Instant.now()),
            toJsonObject(createCloudEvent(TYPE_ENDORSER_INACTIVE, "222", """{"ledger":"ledger1", "did":"did1"}"""))
          )
        )
        Await.result(fut, 500.seconds)
      }
    }
  }

  "SingletonProxy" - {
    "when asked for ledger endorsers (post removal)" - {
      "should return appropriate endorser" in {
        val fut = singletonParentProxy
          .ask{ ref: ActorRef => ForEndorserRegistry(GetEndorsers("ledger1", ref))}
          .mapTo[LedgerEndorsers]
        val ledgerEndorsers = Await.result(fut, 500.seconds)
        ledgerEndorsers.endorsers shouldBe List(Endorser("did2", "verKey2"))
        ledgerEndorsers.latestEndorser shouldBe Option(Endorser("did2", "verKey2"))
      }
    }
  }

  private def createCloudEvent(typ: String, sourceId: String, payload: String): CloudEvent = {
    CloudEventBuilder
      .v1()
      .withId(UUID.randomUUID().toString)
      .withType(typ)
      .withSource(URI.create(s"http://example.com/$sourceId"))
      .withData("application/json", payload.getBytes())
      .withTime(OffsetDateTime.now(ZoneId.of("UTC")))
      .withExtension("company", 1)
      .build()
  }

  private def toJsonObject(event: CloudEvent): JSONObject = {
    new JSONObject(new String(serializedCloudEvent(event)))
  }

  private def serializedCloudEvent(event: CloudEvent): Array[Byte] = {
    EventFormatProvider
      .getInstance
      .resolveFormat(JsonFormat.CONTENT_TYPE)
      .serialize(event)
  }

  implicit val timeout: Timeout = Timeout(10.seconds)
  lazy val endorserRegistryEventHandler = new EndorserRegistryEventHandler(appConfig.config, platform.singletonParentProxy)(
    executionContextProvider.futureExecutionContext)
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  implicit val executionContext: ExecutionContext = ecp.futureExecutionContext
  override def executionContextProvider: ExecutionContextProvider = ecp
}
