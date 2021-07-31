package com.evernym.verity.protocol.protocols.coinflip

import com.evernym.verity.protocol.engine.{Protocol, ProtocolContextApi}
import org.scalatest.PrivateMethodTester
import com.evernym.verity.protocol.testkit.TestsProtocolsImpl
import com.evernym.verity.testkit.BasicFixtureSpec


class EvilCoinFlip(override val ctx: ProtocolContextApi[CoinFlip, Role, Msg, Event, CoinFlipState, String]) extends CoinFlip(ctx) {
  override def doReveal() = {
    ctx.apply(ValueRevealed(ctx.getState.data.myRole.get))
    val committedValue = ctx.getState.getMyRoleData().value.get
    val evilValue = committedValue match {
      case "T" => "H"
      case "H" => "T"
    }
    ctx.send(Reveal(evilValue))
  }
}


object EvilCoinFlipDefinition extends CoinFlipDefTrait {
  override def create(context: ProtocolContextApi[CoinFlip, Role, Msg, Event, CoinFlipState, String]):
  Protocol[CoinFlip, Role, Msg, Event, CoinFlipState, String] =
    new EvilCoinFlip(context)

}


class CoinFlipEvilSpec extends TestsProtocolsImpl(EvilCoinFlipDefinition) with BasicFixtureSpec with PrivateMethodTester {

  "CoinFlip Protocol" - {
    "cheating" - {
      "a chooser changes Heads to Tails" in { f =>

        (f.alice and f.bob) {
          f.alice ~ Start()
          f.alice ~ Continue("Commit")
          f.bob ~ Continue("Commit")
          intercept[RevealDoesntMatchCommitment] {
            f.alice ~ Continue("Reveal")
          }
        }
        //TODO a few things needed...
        // 1. need a clean way to let alice cheat, but bob not
        // 2. need proper strategy for error handling, I see three types:
        //    a. invalid incoming messages (should be handled like other messages... events, altered state and potentially response messages)
        //    b. invalid control messages (maybe exceptions that go right back to the caller? This means an actor system would need a barrier and to handle the asynchronicity)
        //    c. unexpected exceptions (this is like a 500 HTTP status code, where there needs to be a proper fault barrier and graceful reporting)
//        f.bob ~ Continue("Reveal")
//        f.alice.state.data.myRole shouldBe Some(Caller())
//        f.alice.state.isComplete shouldBe false
//        f.alice.state.getTheirRoleData().value should not be None
//        f.alice.state shouldBe a [Error]
//
//        lastMsgFor(blackHole) should not be empty
//        lastMsgFor(blackHole).get shouldBe a[ProblemReport]
      }
    }

  }
}
