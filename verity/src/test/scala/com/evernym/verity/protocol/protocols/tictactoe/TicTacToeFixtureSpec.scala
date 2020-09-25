package com.evernym.verity.protocol.protocols.tictactoe

import com.evernym.verity.protocol.protocols.tictactoe.Board.{O, X}
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeMsgFamily.{AcceptOffer, MakeMove, MakeOffer}
import com.evernym.verity.protocol.testkit.TestsProtocolsImpl
import com.evernym.verity.testkit.BasicFixtureSpec

class TicTacToeFixtureSpec
  extends TestsProtocolsImpl(TicTacToeProtoDef)
    with BasicFixtureSpec
    with SpecHelper {

  def accepted[T](f: Scenario => T): Scenario => T = { s =>
    (s.alice engage s.bob) ~ MakeOffer()
    s.bob ~ AcceptOffer()
    f(s)
  }

  "when tried to send Move for cell which is already used by another" - {
    "should throw exception" in accepted { f =>

      f.alice ~ MakeMove(X, "a1")

      intercept[CellValueNotEmpty] {
        f.bob ~ MakeMove(O, "a1")
      }
    }
  }

  "when tried to play an invalid cell" - {
    "should throw exception" in accepted { f =>

      intercept[InvalidCell] {
        f.alice ~ MakeMove(X, "a100")
      }
    }
  }

  "when tried to move using the wrong symbol (X instead of O)" - {
    "should throw exception" in accepted { f =>

      f.alice ~ MakeMove(X, "a1")

      intercept[CellSymbolAlreadyUsed] {
        f.bob ~ MakeMove(X, "c3")
      }
    }
  }

  "when tried to sent Move for cell 'c3'" - {
    "should process it successfully" in accepted { f =>

      f.alice ~ MakeMove(X, "a1")
      f.bob ~ MakeMove(O, "c3")
      f.bob.state shouldBe a[State.Playing]

      val alicegame = f.alice.state.asInstanceOf[State.Playing].game

      val bobgame = f.bob.state.asInstanceOf[State.Playing].game

      bobgame.board.c3 shouldBe O

      val expected = board("""
        +---+---+---+
        | X |   |   |
        +---+---+---+
        |   |   |   |
        +---+---+---+
        |   |   | O |
        +---+---+---+
        """)

      alicegame.board.draw shouldBe expected

      bobgame.board.draw shouldBe expected
    }
  }


  "after playing many moves" - {
    "should have matching board states" in accepted { f =>

      f.alice ~ MakeMove(X, "a1")
      f.bob ~ MakeMove(O, "c3")
      f.alice ~ MakeMove(X, "a2")
      f.bob ~ MakeMove(O, "c2")

      intercept[CellValueNotEmpty] {
        f.alice ~ MakeMove(X, "c3")
      }

      val expected = board("""
        +---+---+---+
        | X |   |   |
        +---+---+---+
        | X |   | O |
        +---+---+---+
        |   |   | O |
        +---+---+---+
      """)

      f.alice.state shouldBe a[State.Playing]
      f.alice.state.asInstanceOf[State.Playing].game.board.draw shouldBe expected

      f.bob.state shouldBe a[State.Playing]
      f.bob.state.asInstanceOf[State.Playing].game.board.draw shouldBe expected

      f.alice ~ MakeMove(X, "a3")

    }
  }

  "when playing a winning move" - {
    "each protocol should recognize the win" in accepted { f =>

      f.alice ~ MakeMove(X, "a1")
      f.bob ~ MakeMove(O, "c3")
      f.alice ~ MakeMove(X, "a2")
      f.bob ~ MakeMove(O, "c2")

      f.alice.state shouldBe a[State.Playing]
      f.bob.state shouldBe a[State.Playing]

      f.alice ~ MakeMove(X, "a3")

      f.alice.state shouldBe a[State.Finished]
      f.bob.state shouldBe a[State.Finished]
    }
  }

  "when either party tries to move after game is finished" - {
    "should reject move" in accepted { f =>

      f.alice ~ MakeMove(X, "a1")
      f.bob ~ MakeMove(O, "c3")
      f.alice ~ MakeMove(X, "a2")
      f.bob ~ MakeMove(O, "c2")
      f.alice ~ MakeMove(X, "a3")

      f.alice.state shouldBe a[State.Finished]
      f.bob.state shouldBe a[State.Finished]

      intercept[GameAlreadyFinished] {
        f.bob ~ MakeMove(O, "a2")
      }
    }
  }

  "when restarted protocol" - {
    "should recover to same state" in accepted { f =>

      f.alice ~ MakeMove(X, "a1")
      f.bob ~ MakeMove(O, "c3")
      f.alice ~ MakeMove(X, "a2")
      f.bob ~ MakeMove(O, "c2")
      f.alice ~ MakeMove(X, "a3")

      f.alice.state shouldBe a[State.Finished]

      val newAlice = f.alice.resetContainer(f.alice, replayEvents = true)

      newAlice.state shouldBe a[State.Finished]
    }
  }

}
