package com.evernym.verity.protocol.protocols.basicMessage.v_1_0.expire_after_terminal_state

import com.evernym.verity.actor.agent.TypeFormat
import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants.DATA_RETENTION_POLICY
import com.evernym.verity.did.didcomm.v1.decorators.AttachmentDescriptor.extractString
import com.evernym.verity.did.didcomm.v1.decorators.{Base64, AttachmentDescriptor => Attachment}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.OneToOne
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Localization => l10n}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Role.Participator
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.{BasicMessageDefinition, Msg, Signal, State}
import com.evernym.verity.protocol.testkit.DSL._
import com.evernym.verity.protocol.testkit.TestsProtocolsImpl
import com.evernym.verity.testkit.BasicFixtureSpec
import java.util.UUID

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.ExecutionContext
import scala.language.{implicitConversions, reflectiveCalls}


class BasicMessageSpec
  extends TestsProtocolsImpl(BasicMessageDefinition, Option(OneToOne))
  with BasicFixtureSpec {

  import TestingVars._

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  private implicit def EnhancedScenario(s: Scenario) = new {
    val alice: TestEnvir = s(PARTICIPATOR)
    val bob: TestEnvir = s(PARTICIPATOR)
  }

  override val defaultInitParams = Map(
    DATA_RETENTION_POLICY -> """{"expire-after-days":"30 days", "expire-after-terminal-state":true}"""
  )

  "Basic Message Protocol Definition" - {
    "should have two roles" in { _ =>
      BasicMessageDefinition.roles.size shouldBe 1
      BasicMessageDefinition.roles shouldBe Set(Participator)
    }
  }

  "Message Msg" - {
    "produces valid json" in { _ =>
      val msg = Msg.Message(
        l10n(locale = Some("en")),
        "2018-12-13T17:29:34+0000",
        "Hello, World!"
      )
      val threadId = UUID.randomUUID().toString
      val jsonWithType = buildAgentMsg(
        msg,
        UUID.randomUUID().toString,
        threadId,
        BasicMessageDefinition,
        TypeFormat.STANDARD_TYPE_FORMAT
      )

      jsonWithType.msgType.msgName shouldBe "message"
      jsonWithType.jsonStr shouldBe a [String]
    }
  }

  "Message Protocol" - {

    "when Enterprise Driver sends SendMessage control message" - {
      "sender and receiver should both be in the messaging state" in { s =>
        interaction(s.alice, s.bob) {
          s.checkTotalSegments(0)
          s.alice ~ testSendMessage()
          s.checkTotalSegments(0, waitMillisBeforeCheck = 200)
          s.bob expect signal[Signal.ReceivedMessage]

          s.alice.state shouldBe a[State.Messaging]

          s.bob.state shouldBe a[State.Messaging]
        }
      }
    }
    "when Sender sends message" - {
      "Receiver should receive message" in { s =>
        interaction(s.alice, s.bob) {

          s.checkTotalSegments(0)
          s.alice ~ testSendMessage()
          s.checkTotalSegments(0, waitMillisBeforeCheck = 200)

          val result = s.bob expect signal[Signal.ReceivedMessage]
          result.content shouldBe "Hello, World!"
          result.`~l10n` shouldBe l10n(locale = Some("en"))
          result.sent_time shouldBe "2018-12-13T17:29:34+0000"

          s.bob.state shouldBe a[State.Messaging]

          s.alice.state shouldBe a[State.Messaging]
        }
      }
      "Receiver can also send messages " in { s =>
        interaction(s.alice, s.bob) {
          s.checkTotalSegments(0)
          s.alice ~ testSendMessage()
          s.checkTotalSegments(0, waitMillisBeforeCheck = 200)

          s.bob expect signal[Signal.ReceivedMessage]

          s.alice.state shouldBe a[State.Messaging]

          s.bob.state shouldBe a[State.Messaging]

          s.bob ~ testSendMessage()
          s.checkTotalSegments(0, waitMillisBeforeCheck = 200)

          s.alice expect signal[Signal.ReceivedMessage]

          s.alice.state shouldBe a[State.Messaging]

          s.bob.state shouldBe a[State.Messaging]
        }
      }
      "with attachment" - {
        "receiver receives attachment" in { s =>
          interaction(s.alice, s.bob) {                                      // Base64 encoded "Hello, World!"
            val attachment = Attachment(Some("testfile"), Some("application/json"), Base64("SGVsbG8sIFdvcmxkIQ=="), Some("test.json"))
            s.checkTotalSegments(0)
            s.alice ~ testSendMessage(Option(Vector(attachment)))
            s.checkTotalSegments(0, waitMillisBeforeCheck = 200)

            val result = s.bob expect signal[Signal.ReceivedMessage]
            result.content shouldBe "Hello, World!"
            result.`~l10n` shouldBe l10n(locale = Some("en"))
            result.sent_time shouldBe "2018-12-13T17:29:34+0000"
            extractString(result.`~attach`.get(0)) shouldBe "Hello, World!"
          }
        }
      }
    }
  }

  "Negative Cases" - {
    "Sender does not include localization" in { s =>
      interaction (s.alice, s.bob) {
        s.checkTotalSegments(0)
        s.alice ~ SendMessage(
          sent_time="2018-12-13T17:29:34+0000",
          content="Hello, World",
        )
        s.checkTotalSegments(0, waitMillisBeforeCheck = 200)
        var result = s.bob expect signal [Signal.ReceivedMessage]
        result.`~l10n` shouldBe l10n(locale = Some("en"))
      }
    }
    "Attachment does not include required parameters" in { s =>
      interaction(s.alice, s.bob) {                                      // Base64 encoded "Hello, World!"
        val attachment = Attachment(data = Base64("SGVsbG8sIFdvcmxkIQ=="))

        s.checkTotalSegments(0)
        s.alice ~ testSendMessage(Option(Vector(attachment)))
        s.checkTotalSegments(0, waitMillisBeforeCheck = 200)

        val result = s.bob expect signal[Signal.ReceivedMessage]
        result.content shouldBe "Hello, World!"
        result.`~l10n` shouldBe l10n(locale = Some("en"))
        result.sent_time shouldBe "2018-12-13T17:29:34+0000"
        extractString(result.`~attach`.get(0)) shouldBe "Hello, World!"
      }
    }
  }

  override val containerNames: Set[ContainerName] = Set(TestingVars.PARTICIPATOR, TestingVars.PARTICIPATOR)
  override def appConfig: AppConfig = TestExecutionContextProvider.testAppConfig
}

object TestingVars {
  val PARTICIPATOR = "participator"
  val MESSAGE_CONTENT = "Hello, World!"
  val LOCALIZATION = l10n(locale = Some("en"))
  val OUT_TIME = "2018-12-13T17:29:34+0000"

  def testSendMessage(a: Option[Vector[Attachment]] = None): SendMessage = {
    SendMessage(
      LOCALIZATION,
      OUT_TIME,
      MESSAGE_CONTENT,
      a,
    )
  }
}
