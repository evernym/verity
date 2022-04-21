package com.evernym.verity.endorser_registry

import akka.actor.typed.ActorRef
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpecBase
import com.evernym.verity.endorser_registry.EndorserRegistry.{Cmd, Commands, Replies}
import com.evernym.verity.endorser_registry.States.Endorser
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}

import java.util.UUID


class EndorserRegistrySpec
  extends EventSourcedBehaviourSpecBase
    with BasicSpec {

  "EndorserRegistry" - {

    "when added endorser" - {
      "should be successful" in {
        val endorserRegistry = createEndorserRegistry()
        val probe1 = createTestProbe[Replies.EndorserAdded]()
        endorserRegistry ! Commands.AddEndorser("ledger1", "did1", probe1.ref)
        probe1.expectMessageType[Replies.EndorserAdded]

        val probe2 = createTestProbe[Replies.LedgerEndorsers]()
        endorserRegistry ! Commands.GetEndorsers("ledger1", probe2.ref)
        val ledgerEndorsers = probe2.expectMessageType[Replies.LedgerEndorsers]
        ledgerEndorsers.endorsers shouldBe List(Endorser("did1"))
        ledgerEndorsers.latestEndorser shouldBe Option(Endorser("did1"))
      }
    }

    "when added endorser again for given ledger" - {
      "should be successful" in {
        val endorserRegistry = createEndorserRegistry()
        val probe = createTestProbe[Replies.EndorserAdded]()

        endorserRegistry ! Commands.AddEndorser("ledger1", "did1", probe.ref)
        val endorserAdded1 = probe.expectMessageType[Replies.EndorserAdded]
        endorserAdded1.ledger shouldBe "ledger1"
        endorserAdded1.did shouldBe "did1"

        endorserRegistry ! Commands.AddEndorser("ledger1", "did1", probe.ref)
        probe.expectMessageType[Replies.EndorserAdded]

        val probe2 = createTestProbe[Replies.LedgerEndorsers]()
        endorserRegistry ! Commands.GetEndorsers("ledger1", probe2.ref)
        val ledgerEndorsers = probe2.expectMessageType[Replies.LedgerEndorsers]
        ledgerEndorsers.endorsers shouldBe List(Endorser("did1"))
        ledgerEndorsers.latestEndorser shouldBe Option(Endorser("did1"))
      }
    }

    "when added multiple endorsers for given ledger" - {
      "maintains the order of endorser added to the ledger" in {
        val endorserRegistry = createEndorserRegistry()
        val probe = createTestProbe[Replies.EndorserAdded]()

        endorserRegistry ! Commands.AddEndorser("ledger1", "did1", probe.ref)
        probe.expectMessageType[Replies.EndorserAdded]

        endorserRegistry ! Commands.AddEndorser("ledger1", "did2", probe.ref)
        probe.expectMessageType[Replies.EndorserAdded]

        val probe2 = createTestProbe[Replies.LedgerEndorsers]()
        endorserRegistry ! Commands.GetEndorsers("ledger1", probe2.ref)
        val ledgerEndorsers = probe2.expectMessageType[Replies.LedgerEndorsers]
        ledgerEndorsers.endorsers shouldBe Seq(Endorser("did1"), Endorser("did2"))
        ledgerEndorsers.latestEndorser shouldBe Option(Endorser("did2"))
      }
    }

    "when tried to remove endorser" - {
      "should be successful" in {
        val endorserRegistry = createEndorserRegistry()
        val probe = createTestProbe[Replies.EndorserAdded]()

        endorserRegistry ! Commands.AddEndorser("ledger1", "did1", probe.ref)
        probe.expectMessageType[Replies.EndorserAdded]
        endorserRegistry ! Commands.AddEndorser("ledger1", "did2", probe.ref)
        probe.expectMessageType[Replies.EndorserAdded]
        endorserRegistry ! Commands.AddEndorser("ledger1", "did3", probe.ref)
        probe.expectMessageType[Replies.EndorserAdded]

        val probe1 = createTestProbe[Replies.LedgerEndorsers]()
        endorserRegistry ! Commands.GetEndorsers("ledger1", probe1.ref)
        val ledgerEndorsers = probe1.expectMessageType[Replies.LedgerEndorsers]
        ledgerEndorsers.endorsers shouldBe Seq(Endorser("did1"), Endorser("did2"), Endorser("did3"))
        ledgerEndorsers.latestEndorser shouldBe Option(Endorser("did3"))

        val probe2 = createTestProbe[Replies.EndorserRemoved]()
        endorserRegistry ! Commands.RemoveEndorser("ledger1", "did2", probe2.ref)
        val ledgerRemoved = probe2.expectMessageType[Replies.EndorserRemoved]
        ledgerRemoved.ledger shouldBe "ledger1"
        ledgerRemoved.did shouldBe "did2"

        endorserRegistry ! Commands.GetEndorsers("ledger1", probe1.ref)
        val latestLedgerEndorsers = probe1.expectMessageType[Replies.LedgerEndorsers]
        latestLedgerEndorsers.endorsers shouldBe Seq(Endorser("did1"), Endorser("did3"))
        latestLedgerEndorsers.latestEndorser shouldBe Option(Endorser("did3"))
      }
    }
  }

  def createEndorserRegistry(): ActorRef[Cmd] = {
    spawn(EndorserRegistry(UUID.randomUUID().toString, config))
  }

  lazy val config: Config = ConfigFactory.parseString(
    """
      verity {
        endorser-registry {
          snapshot {
            after-every-events = 1
            keep-snapshots = 1
            delete-events-on-snapshots = true
          }
        }
        salt.event-encryption = "test"
      }
      """.stripMargin)
}
