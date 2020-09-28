package com.evernym.verity.protocol.protocols.trustping.v_1_0

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.protocols.trustping.v_1_0.Role.{Receiver, Sender}
import com.evernym.verity.protocol.testkit.DSL.{signal, state}
import com.evernym.verity.protocol.testkit.TestsProtocolsImpl
import com.evernym.verity.testkit.BasicFixtureSpec
import scala.language.{implicitConversions, reflectiveCalls}

class TrustPingProtocolSpec extends TestsProtocolsImpl(TrustPingDefinition) with BasicFixtureSpec {

  import TrustPingVars._

  import scala.language.reflectiveCalls

  lazy val config: AppConfig = new TestAppConfig()

  override val containerNames: Set[ContainerName] = Set(TrustPingVars.SENDER, TrustPingVars.RECEIVER)

  private implicit def EnhancedScenario(s: Scenario) = new {
    val sender: TestEnvir = s(SENDER)
    val receiver: TestEnvir = s(RECEIVER)
  }

  "The TrustPing Protocol" - {
    "has two roles" in { _ =>
      TrustPingDefinition.roles.size shouldBe 2
    }

    "and the roles are Sender and Receiver" in { _ =>
      TrustPingDefinition.roles shouldBe Set(Sender, Receiver)
    }
  }

  "Sender sending Ping" - {
    "standard trust ping" in { s =>
      interaction (s.sender, s.receiver) {
        s.sender ~ Ctl.SendPing(Some("test-ping-comment"), response_requested = true)

        s.sender.expectAs(state[State.SentPing]) { s =>
          s.msg.response_requested.value shouldBe true
          s.msg.comment.value shouldBe "test-ping-comment"
        }

        s.receiver.expectAs(signal[Sig.ReceivedPing]) { msg =>
          msg.ping.response_requested.value shouldBe true
          msg.ping.comment.value shouldBe "test-ping-comment"
        }

        s.receiver expect state[State.ReceivedPing]
        s.receiver ~ Ctl.SendResponse(Some("test-response-comment"))
        s.receiver expect state[State.SentResponded]

        val sentResponse = s.receiver expect signal[Sig.SentResponse]
        sentResponse.relationship shouldBe s.receiver.did_!

        s.sender.expectAs(signal[Sig.ReceivedResponse]) { msg =>
          msg.resp.comment.value shouldBe "test-response-comment"

        }
        s.sender expect state[State.ReceivedResponse]

      }
    }

    "minimal trust ping" in { s =>
      interaction (s.sender, s.receiver) {
        s.sender ~ Ctl.SendPing(None, response_requested = false)

        s.sender.expectAs(state[State.SentPing]) { s =>
          s.msg.response_requested.value shouldBe false
          s.msg.comment shouldBe None
        }

        s.receiver.expectAs(signal[Sig.ReceivedPing]) { msg =>
          msg.ping.response_requested.value shouldBe false
          msg.ping.comment shouldBe None
        }

        s.receiver expect state[State.ReceivedPing]
        s.receiver ~ Ctl.SendResponse(Some("test-response-comment"))
        s.receiver expect state[State.SentResponded]

        val sentResponse = s.receiver expect signal[Sig.SentResponse]
        sentResponse.relationship shouldBe s.receiver.did_!

        s.sender expect state[State.SentPing]

      }
    }
  }



}

object TrustPingVars {
  val SENDER = "sender"
  val RECEIVER = "receiver"
}