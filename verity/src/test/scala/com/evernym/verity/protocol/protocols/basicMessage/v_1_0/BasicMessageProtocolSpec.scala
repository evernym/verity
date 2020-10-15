package com.evernym.verity.protocol.protocols.basicMessage.v_1_0

import java.util.UUID

import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.agentmsg.msgcodec.StandardTypeFormat
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.Envelope1
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Localization => l10n, Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.BasicMessage._
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Role.{Receiver, Sender}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Signal._
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Signal
import com.evernym.verity.protocol.testkit.DSL._
import com.evernym.verity.protocol.testkit.{MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.Base64Util
import com.evernym.verity.util.TimeUtil._
import org.joda.time.base.BaseDateTime
import org.scalatest.Assertion

import scala.language.{implicitConversions, reflectiveCalls}


class BasicMessageSpec
  extends TestsProtocolsImpl(BasicMessageDefinition)
  with BasicFixtureSpec {

  import TestingVars._

  lazy val config: AppConfig = new TestAppConfig

  private implicit def EnhancedScenario(s: Scenario) = new {
    val sender: TestEnvir = s(SENDER)
    val receiver: TestEnvir = s(RECEIVER)
  }

  "Basic Message Protocol Definition" - {
    "should have two roles" in { _ =>
      BasicMessageDefinition.roles.size shouldBe 2
      BasicMessageDefinition.roles shouldBe Set(Sender, Receiver)
    }
  }

  "Message Msg" - {
    "produces valid json" in { _ =>
      val msg = Msg.Message(
        l10n(locale = Some("en")),
        BaseTiming(out_time = Some("2018-12-13T17:29:34+0000")),
        "Hello, World!"
      )
      val threadId = UUID.randomUUID().toString
      val jsonWithType = buildAgentMsg(
        msg,
        UUID.randomUUID().toString,
        threadId,
        BasicMessageDefinition,
        StandardTypeFormat
      )

      jsonWithType.msgType.msgName shouldBe "message"
      jsonWithType.jsonStr shouldBe a [String]
    }
  }

  "Message Protocol" - {

    "when Enterprise Driver sends SendMessage control message" - {
      "sender and receiver should both be in the messaging state" in { s =>
        interaction(s.sender, s.receiver) {

          s.sender ~ testSendMessage()

          s.receiver expect signal[Signal.ReceivedMessage]

          s.sender.state shouldBe a[State.Messaging]

          s.receiver.state shouldBe a[State.Messaging]
        }
      }
    }
    "when Sender sends message" - {
      "Receiver should receive message" in { s =>
        interaction(s.sender, s.receiver) {

          s.sender ~ testSendMessage()

          val result = s.receiver expect signal[Signal.ReceivedMessage]
          result.content shouldBe "Hello, World!"
          result.`~l10n` shouldBe l10n(locale = Some("en"))
          result.sent_time shouldBe BaseTiming(out_time = Some("2018-12-13T17:29:34+0000"))

          s.receiver.state shouldBe a[State.Messaging]

          s.sender.state shouldBe a[State.Messaging]
        }
      }
    }
      "Receiver can also send messages " in { s =>
        interaction (s.sender, s.receiver) {
          s.sender ~ testSendMessage()

          s.receiver expect signal [Signal.ReceivedMessage]

          s.sender.state shouldBe a[State.Messaging]

          s.receiver.state shouldBe a[State.Messaging]

          s.receiver ~ testSendMessage()

          s.sender expect signal [Signal.ReceivedMessage]

          s.sender.state shouldBe a[State.Messaging]

          s.receiver.state shouldBe a[State.Messaging]
        }
    }

  }

  "Negative Cases" - {
    "Sender does not include localization" in { s =>
      interaction (s.sender, s.receiver) {
        s.sender ~ SendMessage(
          sent_time=BaseTiming(out_time = Some("2018-12-13T17:29:34+0000")),
          content="Hello, World",
        )

        var result = s.receiver expect signal [Signal.ReceivedMessage]
        result.`~l10n` shouldBe l10n(locale = Some("en"))
      }
    }
  }

  override val containerNames: Set[ContainerName] = Set(TestingVars.SENDER, TestingVars.RECEIVER)
}

object TestingVars extends CommonSpecUtil {
  val SENDER = "sender"
  val RECEIVER = "receiver"
  val MESSAGE_CONTENT = "Hello, World!"
  val LOCALIZATION = l10n(locale = Some("en"))
  val OUT_TIME = BaseTiming(out_time = Some("2018-12-13T17:29:34+0000"))

  def testSendMessage(): SendMessage = {
    SendMessage(
      LOCALIZATION,
      OUT_TIME,
      MESSAGE_CONTENT
    )
  }
}
