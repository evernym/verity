package com.evernym.verity.protocol.protocols.coinflip

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.engine.ProtocolRegistry.DriverGen
import com.evernym.verity.protocol.testkit.{ContainerNotFoundException, InteractionController, SimpleControllerProviderInputType, TestsProtocolsImpl}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.ExecutionContext

class CoinFlipSpec extends TestsProtocolsImpl(CoinFlipDefinition) with BasicFixtureSpec {

  "CoinFlip Protocol Definition" - {
    "has two roles" in { _ =>
      CoinFlipDefinition.roles.size shouldBe 2
      CoinFlipDefinition.roles shouldBe Set(Caller(), Chooser())
    }
  }

  "Companion Object" - {
    "chooseCoinCommitment" in { _ =>
      val ct = new CoinTools {}
      val iters = 1000
      val x = Seq.fill(iters)(ct.chooseCoinValue())
      val y = x.groupBy(identity).mapValues(_.length)

      y.size shouldBe 2
      y.keys.toList.sortBy(identity) shouldBe List("H","T")
      y.values.sum shouldBe iters
      (y(ct.HEAD).toDouble / iters) shouldBe 0.5 +- 0.3
      // the odds of having less than 300 heads out of 1000 tosses is less than one in one billion trillion trillion
    }

  }
  "CoinFlip Protocol" - {
    "step by step" - {

      "a 'Start' msg should reach New state" in { f =>

        //        (f.alice engage f.bob) ~ Start()

        (f.alice and f.bob) {

          f.alice ~ Start()

          f.alice.state.data.myRole shouldBe Some(Caller())
          f.alice.state shouldBe a[New]
          f.alice.state.getTheirRoleData().commitment shouldBe None

          // Bob's container should not be set yet because Start() doesn't send a protocol message
          intercept [ContainerNotFoundException] {
            f.bob.container_!
          }
        }

      }

      "continuing to commit for alice should reach 'Committed' state for alice" in { f =>

        (f.alice and f.bob) {
          f.alice ~ Start()
          f.alice ~ Continue("Commit")

          f.alice.state.data.myRole shouldBe Some(Caller())
          f.alice.state shouldBe a[Committed]
          f.alice.state.getTheirRoleData().commitment shouldBe None

          f.bob.state.data.myRole shouldBe Some(Chooser())
          f.bob.state.data.callerData.commitment should not be empty
        }

      }

      "continuing to commit for both should reach 'Committed' state for both" in { f =>

        (f.alice and f.bob) {
          f.alice ~ Start()
          f.alice ~ Continue("Commit")
          f.bob ~ Continue("Commit")

          f.alice.state.data.myRole shouldBe Some(Caller())
          f.alice.state shouldBe a[Committed]
          f.alice.state.getTheirRoleData().commitment should not be None
          f.alice.state.getTheirRoleData().value shouldBe None

          f.bob.state.data.myRole shouldBe Some(Chooser())
          f.bob.state shouldBe a[Committed]
          f.bob.state.getTheirRoleData().commitment should not be None
          f.bob.state.getTheirRoleData().value shouldBe None
        }
      }

      "continuing to reveal for alice should reach Revealed state for alice" in { f =>

        (f.alice and f.bob) {
          f.alice ~ Start()
          f.alice ~ Continue("Commit")
          f.bob ~ Continue("Commit")
          f.alice ~ Continue("Reveal")

          f.alice.state.data.myRole shouldBe Some(Caller())
          f.alice.state shouldBe a[Revealed]
          f.alice.state.getTheirRoleData().commitment should not be None
          f.alice.state.getTheirRoleData().value shouldBe None

          f.bob.state.data.myRole shouldBe Some(Chooser())
          f.bob.state shouldBe a[Committed]
          f.bob.state.getTheirRoleData().commitment should not be None
          f.bob.state.getTheirRoleData().value should not be None
        }
      }

      "continuing to reveal for both should reach Revealed state for both" in { f =>
        (f.alice and f.bob) {
          f.alice ~ Start()
          f.alice ~ Continue("Commit")
          f.bob   ~ Continue("Commit")
          f.alice ~ Continue("Reveal")
          f.bob   ~ Continue("Reveal")

          f.alice.state.data.myRole shouldBe Some(Caller())
          f.alice.state.isComplete shouldBe true
          f.alice.state.getTheirRoleData().commitment should not be None
          f.alice.state.getTheirRoleData().value should not be None

          f.bob.state.data.myRole shouldBe Some(Chooser())
          f.bob.state shouldBe a[Revealed]
          f.bob.state.getTheirRoleData().commitment should not be None
          f.bob.state.getTheirRoleData().value should not be None
        }
      }
    }
    "interactive" - {
      "a 'Start' msg should start and complete the protocol successfully" in { f =>

        val controllerProvider: DriverGen[SimpleControllerProviderInputType] =
          Option{ (i: SimpleControllerProviderInputType, e: ExecutionContext) =>
            new InteractionController(i) {
              override def signal[A]: SignalHandler[A] = {
                case SignalEnvelope(dp: ShouldContinue, _, _, _, _) => Some(Continue(dp.send))
              }
            }
          }

        f.setup("alice", controllerProvider)
        f.setup("bob", controllerProvider)

        //this is processed by alice's protocol instance and applied to her state
        (f.alice and f.bob) {
          f.alice ~ Start()

          f.alice.state.isComplete shouldBe true
          f.alice.state.data.myRole shouldBe Some(Caller())

          f.bob.state.isComplete shouldBe true
          f.bob.state.data.myRole shouldBe Some(Chooser())

          //TODO need a better way to test if states are not equal
          f.alice.state.getClass should not be f.bob.state.getClass //They must not end up at the same state (only one winner/loser)
          f.alice.state.getMyRoleData().value shouldBe f.bob.state.getTheirRoleData().value
          f.bob.state.getMyRoleData().value shouldBe f.alice.state.getTheirRoleData().value
        }
      }
    }
  }

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}
