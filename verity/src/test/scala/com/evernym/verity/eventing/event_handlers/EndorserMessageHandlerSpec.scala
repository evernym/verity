package com.evernym.verity.eventing.event_handlers

import akka.actor.ActorRef
import akka.pattern.extended.ask
import akka.actor.typed.scaladsl.adapter._
import com.evernym.verity.actor.cluster_singleton.ForEndorserRegistry
import com.evernym.verity.endorser_registry.EndorserRegistry.Commands.GetEndorsers
import com.evernym.verity.endorser_registry.EndorserRegistry.Replies.LedgerEndorsers
import com.evernym.verity.endorser_registry.States.Endorser
import com.evernym.verity.eventing.ports.consumer.{Message, Metadata}

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration._


class EndorserMessageHandlerSpec
  extends EventHandlerSpecBase {

  "EndorserRegistryEventHandler" - {
    "when received AddEndorser cloud event message" - {
      "should handle it successfully" in {
        val fut = endorserRegistryEventHandler.handleMessage(
          Message(
            Metadata(TOPIC_SSI_ENDORSER, partition = 1, offset = 0, Instant.now()),
            toJsonObject(createCloudEvent(EVENT_ENDORSER_ACTIVATED_V1, "pinstid1", """{"ledgerprefix":"ledger1", "endorserdid":"did1"}"""))
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
            Metadata(TOPIC_SSI_ENDORSER, partition = 1, offset = 0, Instant.now()),
            toJsonObject(createCloudEvent(EVENT_ENDORSER_ACTIVATED_V1, "111", """{"ledgerprefix":"ledger1", "endorserdid":"did2"}"""))
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
        ledgerEndorsers.endorsers shouldBe List(Endorser("did1"), Endorser("did2"))
        ledgerEndorsers.latestEndorser shouldBe Option(Endorser("did2"))
      }
    }
  }

  "EndorserRegistryEventHandler" - {
    "when received RemoveEndorser cloud event message" - {
      "should handle it successfully" in {
        val fut = endorserRegistryEventHandler.handleMessage(
          Message(
            Metadata(TOPIC_SSI_ENDORSER, partition = 1, offset = 1, Instant.now()),
            toJsonObject(createCloudEvent(EVENT_ENDORSER_DEACTIVATED_V1, "222", """{"ledgerprefix":"ledger1", "endorserdid":"did1"}"""))
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
        ledgerEndorsers.endorsers shouldBe List(Endorser("did2"))
        ledgerEndorsers.latestEndorser shouldBe Option(Endorser("did2"))
      }
    }
  }

  lazy val endorserRegistryEventHandler = new EndorserMessageHandler(appConfig.config, platform.singletonParentProxy)(
    executionContextProvider.futureExecutionContext)
}
