package com.evernym.verity.protocol.protocols.basicMessage.v_1_0

import java.util.UUID

import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.agentmsg.msgcodec.StandardTypeFormat
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.Envelope1
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.BasicMessage._
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Role.{Sender, Receiver}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Signal._
import com.evernym.verity.protocol.testkit.DSL._
import com.evernym.verity.protocol.testkit.{MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.Base64Util
import com.evernym.verity.util.TimeUtil._
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

  def checkStatus(envir: TestEnvir, expectedStatus: StatusReport): Unit = {
    envir clear signals
    envir ~ GetStatus()
    (envir expect signal [StatusReport]) shouldBe expectedStatus
  }

  "Basic Message Protocol Definition" - {
    "should have two roles" in { _ =>
      BasicMessageDefinition.roles.size shouldBe 2
      BasicMessageDefinition.roles shouldBe Set(Sender, Receiver)
    }
  }


  "Message Msg" - {
    "produces valid json" in { _ =>
      val t = MockableWalletAccess.randomSig()
      val msg = Msg.Message(
        "en",
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
        interaction (s.sender, s.receiver) {

          s.sender ~ testSendMessage(EXPIRATION_TIME)

          //s.receiver expect signal [Signal.AnswerNeeded]

          s.sender.state shouldBe a[State.Messaging]
          checkStatus(s.sender, StatusReport("Messaging"))

          s.receiver.state shouldBe a[State.Messaging]
          checkStatus(s.receiver, StatusReport("Messaging"))
        }
      }
    }
//    "when Sender sends message" - {
//      "Receiver should receive message" in { s =>
//        interaction (s.sender, s.receiver) {
//
//          s.sender ~ testSendMessage(EXPIRATION_TIME)
//
//          s.receiver walletAccess MockableWalletAccess()
//
//          s.sender walletAccess MockableWalletAccess()
//
//          s.receiver.state shouldBe a[State.Messaging]
//          checkStatus(s.receiver, StatusReport("Message Received"))
//
//          s.sender.state shouldBe a[State.Messaging]
//          val result = s.sender expect signal [Signal.ReceiveMessage]
//          result.valid_signature shouldBe true
//          checkStatus(
//            s.sender,
//            StatusReport("AnswerReceived", expectedAnswer(CORRECT_ANSWER))
//          )
//        }
//      }
//    }
  }

//  def expectedAnswer(answer: String,
//                     validAnswer: Boolean = true,
//                     validSig: Boolean = true,
//                     notExpired: Boolean = true): Some[AnswerGiven] = {
//    Some(
//      AnswerGiven(
//        answer,
//        valid_answer = validAnswer,
//        valid_signature = validSig,
//        not_expired = notExpired
//      )
//    )
//  }

//  def compareQuestion(q1: Msg.Question, q2: Msg.Question): Assertion = {
//    val normalizedResponses  = q1.valid_responses
//      .zip(q2.valid_responses)
//      .map{ x =>
//        (x._1.copy(nonce = ""), x._2.copy(nonce = ""))
//      }
//      .unzip
//    assert(
//      q1.copy(valid_responses = normalizedResponses._1) == q2.copy(valid_responses = normalizedResponses._2)
//    )
//  }

//  def withDefaultWalletAccess(s: Scenario, f: => Unit): Unit = {
//    s.receiver walletAccess MockableWalletAccess()
//    s.sender walletAccess MockableWalletAccess()
//  }

//  def askAndAnswer(s: Scenario,
//                   ex: Option[BaseTiming] = EXPIRATION_TIME,
//                   out: Option[BaseTiming] = OUT_TIME): Unit = {
//
//    s.questioner ~ testAskQuestion(ex)
//    s.questioner expect state [State.QuestionSent]
//    s.responder expect signal [Signal.AnswerNeeded]
//    s.responder expect state [State.QuestionReceived]
//
//    s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))
//    s.responder expect state [State.AnswerSent]
//    s.questioner expect state [State.AnswerReceived]
//  }

  override val containerNames: Set[ContainerName] = Set(TestingVars.SENDER, TestingVars.RECEIVER)
}

object TestingVars extends CommonSpecUtil {
  val SENDER = "sender"
  val RECEIVER = "receiver"
  val MESSAGE_CONTENT = "Hello, World!"
  val INTERNATIONALIZATION = "en"

  val EXPIRATION_TIME = Option(BaseTiming(expires_time = Some("2118-12-13T17:29:06+0000")))
  val TIME_EXPIRED = Option(BaseTiming(expires_time = Some("2017-12-13T17:29:06+0000")))
  val OUT_TIME = BaseTiming(out_time = Some("2018-12-13T17:29:34+0000"))

  def testSendMessage(time: Option[BaseTiming]): SendMessage = {
    SendMessage(
      INTERNATIONALIZATION,
      OUT_TIME,
      MESSAGE_CONTENT
    )
  }

//  def testQuestion(time: Option[BaseTiming]): Msg.Question =
//    Msg.Question(QUESTION_TEXT, QUESTION_DETAIL, VALID_RESPONSES, time)
//
//  def testAnswer(time: Option[BaseTiming], sigData: Sig = TEST_SIGNATURE_DATA): Msg.Answer =
//    Msg.Answer(sigData)
}
