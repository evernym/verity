package com.evernym.verity.protocol.protocols.connections.v_1_0

import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.ProtocolRegistry._
import com.evernym.verity.protocol.engine.{DIDDoc, SignalEnvelope}
import com.evernym.verity.protocol.protocols.connections.v_1_0.Ctl.{Accept, TheirDidDocUpdated, TheirDidUpdated}
import com.evernym.verity.protocol.protocols.connections.v_1_0.Role.{Invitee, Inviter}
import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.UpdateTheirDid
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.{InteractionController, MockableWalletAccess, SimpleControllerProviderInputType, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec


class ConnectionsSpec extends TestsProtocolsImpl(ConnectionsDef)
  with BasicFixtureSpec with CommonSpecUtil {

  "The Connections Protocol" - {
    "has two roles" in { _ =>
      ConnectionsDef.roles.size shouldBe 2
    }

    "and the roles are Inviter and Invitee" in { _ =>
      ConnectionsDef.roles shouldBe Set(Inviter, Invitee)
    }
  }

  "Invitee sending Accept message for invitation with DID" - {
    implicit val system = new TestSystem()

    val inviter = setup("inviter")
    val invitee = setup("invitee")

    "should respond with not supported" in { s =>
      invitee walletAccess MockableWalletAccess()
      (invitee engage inviter) ~ Accept("myLabel", "http://example.com?c_i=eyJsYWJlbCI6InRlc3QtbGFiZWwiLCJkaWQiOiJub3QgYSBESUQuIEZpeCBtZSEhISJ9")
      invitee expect signal [Signal.UnhandledError]
      invitee.state shouldBe a[State.Initialized]
    }
  }

  "Invitee sending Accept message for invitation with keys " - {
    implicit val system = new TestSystem()

    val inviter = setup("inviter")
    val invitee = setup("invitee")

    "role changing to invitee" in { s =>
      invitee walletAccess MockableWalletAccess()
      (invitee engage inviter) ~ Accept("myLabel", "http://example.com?c_i=eyJsYWJlbCI6InRlc3QtbGFiZWwiLCJzZXJ2aWNlRW5kcG9pbnQiOiJpbnZpdGVyIiwicmVjaXBpZW50S2V5cyI6WyIyVTgyVDQ4TFc1bVI0dERKbkpXbVY4b1U4NUtHSjkzcVQ5RHRQVWZ2UEVDUyJdLCJyb3V0aW5nS2V5cyI6WyJzb21lIHJvdXRpbmcga2V5LiBGaXggbWUhISEiXX0=")
      invitee.role shouldBe Role.Invitee
    }

    "protocol transitioning to invited state" in { s =>
      invitee.state shouldBe a[State.Accepted]
    }

    "should get a signal" in { _ =>
      invitee expect signal[Signal.InvitedWithKey]
    }
  }

  "Invitee accepting invitation for Key invite" - {
    implicit val system = new TestSystem()

    inviter = setup("inviter", odg = inviterControllerProvider)
    invitee = setup("invitee", odg = inviteeControllerProvider)

    invitee walletAccess MockableWalletAccess()
    inviter walletAccess MockableWalletAccess()

    val invUrl: Option[String] = Option("http://example.com?c_i=eyJsYWJlbCI6InRlc3QtbGFiZWwiLCJzZXJ2aWNlRW5kcG9pbnQiOiJpbnZpdGVyIiwicmVjaXBpZW50S2V5cyI6WyIyVTgyVDQ4TFc1bVI0dERKbkpXbVY4b1U4NUtHSjkzcVQ5RHRQVWZ2UEVDUyJdLCJyb3V0aW5nS2V5cyI6WyJzb21lIHJvdXRpbmcga2V5LiBGaXggbWUhISEiXX0=")

    "state should change to ConnRequestSent" in { _ =>
      (invitee engage inviter) ~ Accept("myLabel", invUrl.get)

      invitee expect signal[Signal.InvitedWithKey]
      invitee expect signal[Signal.ConnRequestSent]
      invitee.roster.participants.size shouldBe 2

      inviter expect signal[Signal.ConnRequestReceived]
      inviter expect signal[Signal.ConnResponseSent]
      inviter.roster.participants.size shouldBe 2

      invitee.state shouldBe a[State.Completed]
      inviter.state shouldBe a[State.Completed]
    }
  }

  var invitee: TestEnvir = _
  var inviter: TestEnvir = _

  lazy val inviteeControllerProvider = { i: SimpleControllerProviderInputType =>
    new InteractionController(i) {
      override def signal[A]: SignalHandler[A] = {
        case SignalEnvelope(stdd: Signal.SetupTheirDidDoc, _, _, _, _) =>
          Option(TheirDidDocUpdated(inviteeDIDDoc.id, inviteeDIDDoc.verkey, inviteeDIDDoc.routingKeys))
        case SignalEnvelope(_: UpdateTheirDid, _, _, _, _) =>
          Option(TheirDidUpdated())
        case se: SignalEnvelope[A] =>
          super.signal(se)
      }
    }
  }

  lazy val inviterControllerProvider = { i: SimpleControllerProviderInputType =>
    new InteractionController(i) {
      override def signal[A]: SignalHandler[A] = {
        case SignalEnvelope(udd: Signal.SetupTheirDidDoc, _, _, _, _) =>
          Option(TheirDidDocUpdated(inviterDIDDoc.id, inviterDIDDoc.verkey, inviterDIDDoc.routingKeys))
        case se: SignalEnvelope[A] =>
          super.signal(se)
      }
    }
  }

  lazy val inviterDIDDoc: DIDDoc = {
    val dd = generateNewDid(Option("11111111111111111111111111111111"))
    DIDDoc(dd.DID, dd.verKey, "http://localhost:9001/agency/msg", Vector.empty)
  }

  lazy val inviteeDIDDoc: DIDDoc = {
    val dd = generateNewDid(Option("22222222222222222222222222222222"))
    DIDDoc(dd.DID, dd.verKey, "http://localhost:9002/agency/msg", Vector.empty)
  }
}
