package com.evernym.verity.protocol.protocols.tictactoe

import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.{DebugProtocols, SignalEnvelope, SimpleControllerProviderInputType}
import com.evernym.verity.protocol.protocols.tictactoe.Board.{O, X}
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeMsgFamily.{AcceptOffer, MakeOffer, _}
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.{InteractionController, ProtocolTestKit}
import com.evernym.verity.util.Conversions._
import com.evernym.verity.testkit.BasicSpec


import scala.language.postfixOps



class TicTacToeSpec
  extends ProtocolTestKit(TicTacToeProtoDef)
    with BasicSpec
    with SpecHelper
    with DebugProtocols {

  "The TicTacToe Protocol" - {
    "works with new Domain and Relationship based approach" in {

      implicit val system = new TestSystem()

      val alice = setup("alice")
      val bob = setup("bob")

      alice connect bob

      interaction (alice, bob) {

        alice ~ MakeOffer()

        alice expect signal [OfferSent]

        bob expect signal [AskAccept]

        bob ~ AcceptOffer()

        alice expect signal [OfferAccepted]

        alice ~ MakeMove(X, "a1")

      }
    }
  }

  "The TicTacToe Protocol" - {
    "has four roles" in {
      TicTacToeProtoDef.roles.size shouldBe 2
    }
  }

  "A participant" - {
    "when sending OFFER to another to play TicTacToe game " - {
      "should result in OFFERED state for sender and recipient" in {
        implicit val system = new TestSystem()

        val alice = setup("alice")
        val bob = setup("bob")

        (alice engage bob) ~ MakeOffer()

        //NOTE: stateVersion is 2 because it would have already advanced to 1 during initialization
        alice.stateVersion shouldBe 2

        bob.stateVersion shouldBe 2

        alice.state shouldBe a[State.Offered]
        alice.role shouldBe PlayerA()

        bob.state shouldBe a[State.Offered]
        bob.role shouldBe PlayerB()
      }
    }

    "when offered to play" - {
      "can accept" in {
        implicit val sys = new TestSystem()
        val alice = setup("alice")
        val bob = setup("bob")

        (alice engage bob) ~ MakeOffer()

        bob ~ AcceptOffer()

        alice.state shouldBe a[State.Accepted]

        bob.state shouldBe a[State.Accepted]

      }
    }

    "when sent Move msg for cell 'a1'" - {
      "should process it successfully" in {
        implicit val sys = new TestSystem()
        val alice = setup("alice")
        val bob = setup("bob")

        (alice engage bob) ~ MakeOffer()

        bob ~ AcceptOffer()

        alice ~ MakeMove(X, "a1")

        alice.state shouldBe a[State.Playing]

        val aliceState = alice.state.asInstanceOf[State.Playing]

        aliceState.lastMover(alice.backstate.roster).value shouldBe alice.participantId

        aliceState.game.board.draw shouldBe board("""
          +---+---+---+
          | X |   |   |
          +---+---+---+
          |   |   |   |
          +---+---+---+
          |   |   |   |
          +---+---+---+
        """)

        aliceState.game.board.a1 shouldBe X

        bob.state shouldBe a[State.Playing]
        val bobState = bob.state.asInstanceOf[State.Playing]

        bobState.lastMover(bob.backstate.roster).value shouldBe alice.participantId

        bobState.game.board.a1 shouldBe X

      }
    }

    "when sent Move msg again when it is not her turn" - {
      "should throw exception" in {

        implicit val sys = new TestSystem()
        val alice = setup("alice")
        val bob = setup("bob")

        (alice engage bob) ~ MakeOffer()

        bob ~ AcceptOffer()

        alice ~ MakeMove(X, "a1")

        alice.state shouldBe a[State.Playing]
        alice.state.asInstanceOf[State.Playing].lastMover(alice.backstate.roster).value shouldBe alice.participantId

        intercept[NotYourTurn] {
          alice ~ MakeMove(X, "a2")
        }

      }
    }
  }

  "when using a driver configured for auto-accept" - {
    "should see offers accepted automatically" in {

      implicit val system = new TestSystem()

      val controllerProvider = { i: SimpleControllerProviderInputType =>
        new InteractionController(i) {
          override def signal[A]: SignalHandler[A] = {
            case SignalEnvelope(_: AskAccept, _, _, _, _) => Some(AcceptOffer())
            case SignalEnvelope(_,_,_,_,_) => None
          }
        }
      }

      val alice = setup("alice")
      val bob = setup("bob", controllerProvider)

      (alice engage bob) ~ MakeOffer()

      alice.state shouldBe a[State.Accepted]
      bob.state shouldBe a[State.Accepted]

    }
  }

  "when using a driver configured for disconnected auto-accept" - {
    "should see offers accepted automatically" in {

      implicit val system = new TestSystem()

      val controllerProvider = { i: SimpleControllerProviderInputType =>
        new InteractionController(i) {
          override def signal[A]: SignalHandler[A] = {
            case SignalEnvelope(_: AskAccept, _, _, _, _) =>
              control(AcceptOffer())
              None
            case SignalEnvelope(_,_,_,_,_) => None
          }
        }
      }

      val alice = setup("alice")
      val bob = setup("bob", controllerProvider)

      (alice engage bob) ~ MakeOffer()

      alice.state shouldBe a[State.Accepted]
      bob.state shouldBe a[State.Accepted]


    }
  }

  "when the board is full" - {
    "the game should be finished" in {

      implicit val system = new TestSystem()
      val alice = setup("alice")
      val bob = setup("bob")

      (alice engage bob) ~ MakeOffer()

      bob ~ AcceptOffer()

      alice ~ MakeMove(X, "a1")
      bob   ~ MakeMove(O, "a2")
      alice ~ MakeMove(X, "a3")
      bob   ~ MakeMove(O, "b1")
      alice ~ MakeMove(X, "b3")
      bob   ~ MakeMove(O, "b2")
      alice ~ MakeMove(X, "c1")
      bob   ~ MakeMove(O, "c3")

      alice.state shouldBe a [State.Playing]
      bob.state shouldBe a [State.Playing]

      alice ~ MakeMove(X, "c2")

      alice.state shouldBe a [State.Finished]
      bob.state shouldBe a [State.Finished]
    }
  }

}
