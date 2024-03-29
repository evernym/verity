package com.evernym.verity.protocol.protocols.committedAnswer.v_1_0

import java.util.UUID

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.TypeFormat
import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants.DATA_RETENTION_POLICY
import com.evernym.verity.protocol.engine.Envelope1
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.CommittedAnswerProtocol._
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Role.{Questioner, Responder}
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Signal.{AnswerGiven, StatusReport}
import com.evernym.verity.protocol.testkit.DSL._
import com.evernym.verity.protocol.testkit.{MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.{Base64Util, TestExecutionContextProvider}
import com.evernym.verity.util.TimeUtil._
import org.scalatest.Assertion

import scala.concurrent.ExecutionContext
import scala.language.{implicitConversions, reflectiveCalls}


class CommittedAnswerProtocolSpec
  extends TestsProtocolsImpl(CommittedAnswerDefinition)
  with BasicFixtureSpec {

  import TestingVars._

  private implicit def EnhancedScenario(s: Scenario) = new {
    val questioner: TestEnvir = s(QUESTIONER)
    val responder: TestEnvir = s(RESPONDER)
  }

  override val defaultInitParams = Map(
    DATA_RETENTION_POLICY -> "30 day"
  )

  def checkStatus(envir: TestEnvir, expectedStatus: StatusReport): Unit = {
    envir clear signals
    envir ~ GetStatus()
    (envir expect signal [StatusReport]) shouldBe expectedStatus
  }

  "Question Protocol Definition" - {
    "should have two roles" in { _ =>
      CommittedAnswerDefinition.roles.size shouldBe 2
      CommittedAnswerDefinition.roles shouldBe Set(Questioner, Responder)
    }
  }

  "Question Answer Msg" - {
    "Answer Msg" - {
      "produces valid json" in { _ =>
        val t = MockableWalletAccess.randomSig()
        val msg = Msg.Answer(
          Sig(t.get.signatureResult.toBase64, "SDFSDFSDFSDF", nowDateString)
        )
        val threadId = UUID.randomUUID().toString
        val jsonWithType = buildAgentMsg(
          msg,
          UUID.randomUUID().toString,
          threadId,
          CommittedAnswerDefinition,
          TypeFormat.STANDARD_TYPE_FORMAT
        )

        jsonWithType.msgType.msgName shouldBe "answer"
        jsonWithType.jsonStr shouldBe a [String]
      }
    }

  }

  "Question Protocol" - {

    "when Enterprise Driver sends AskQuestion control message" - {
      "questioner and responder should both transition to correct states" in { s =>
        interaction (s.questioner, s.responder) {

          s.questioner ~ testAskQuestion(EXPIRATION_TIME)

          s.responder expect signal [Signal.AnswerNeeded]

          s.questioner.state shouldBe a[State.QuestionSent]
          checkStatus(s.questioner, StatusReport("QuestionSent"))

          s.responder.state shouldBe a[State.QuestionReceived]
          checkStatus(s.responder, StatusReport("QuestionReceived"))
        }
      }
    }

    "when Responder sends answer" - {
      "Questioner should accept answer" in { s =>
        interaction (s.questioner, s.responder) {

          s.questioner ~ testAskQuestion(EXPIRATION_TIME)

          s.responder expect signal [Signal.AnswerNeeded]

          s.responder walletAccess MockableWalletAccess()

          s.questioner walletAccess MockableWalletAccess()

          s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))

          s.responder.state shouldBe a[State.AnswerSent]
          checkStatus(s.responder, StatusReport("AnswerSent"))

          s.questioner.state shouldBe a[State.AnswerReceived]
          val result = s.questioner expect signal [Signal.AnswerGiven]
          result.valid_signature shouldBe true
          checkStatus(
            s.questioner,
            StatusReport("AnswerReceived", expectedAnswer(CORRECT_ANSWER))
          )
        }
      }
    }

    "when Questioner verifies response" - {
      "should have state of AnswerReceived with hasValidSig equal to false" in { s =>
        interaction (s.questioner, s.responder) {
          s.questioner walletAccess MockableWalletAccess.alwaysVerifyAs(false)

          s.questioner ~ testAskQuestion(EXPIRATION_TIME)

          s.responder walletAccess MockableWalletAccess()

          s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))

          s.questioner expect signal [Signal.AnswerGiven]

          val adState = s.questioner expect state [State.AnswerReceived]

          adState.validStatus.value.signatureValidity shouldBe false

          checkStatus(
            s.questioner,
            StatusReport("AnswerReceived", expectedAnswer(CORRECT_ANSWER, validSig = false))
          )

          s.responder expect state [State.AnswerSent]

          checkStatus(s.responder, StatusReport("AnswerSent", None))
        }
      }

      "should have state of AnswerDelivered with valid response" in { s =>
        interaction (s.questioner, s.responder) {

          withDefaultWalletAccess(s, {
            askAndAnswer(s)

            val answerDelivered = s.questioner expect state[State.AnswerReceived]
            answerDelivered.validStatus.value.signatureValidity shouldBe true

            checkStatus(
              s.questioner,
              StatusReport("AnswerReceived", expectedAnswer(CORRECT_ANSWER))
            )

            s.responder expect state[State.AnswerSent]
            checkStatus(s.responder, StatusReport("AnswerSent", None))
          })
        }
      }

      "should have a state indicating an invalid signature when signature check fails" in { s =>
        interaction(s.questioner, s.responder) {

          s.questioner walletAccess MockableWalletAccess.alwaysVerifyAs(false)
          s.responder walletAccess MockableWalletAccess()

          askAndAnswer(s)

          val signalMsg = s.questioner expect signal [Signal.AnswerGiven]

          signalMsg.valid_signature should not be true
          signalMsg.answer shouldBe CORRECT_ANSWER

          checkStatus(
            s.questioner,
            StatusReport("AnswerReceived", expectedAnswer(CORRECT_ANSWER, validSig = false))
          )
        }
      }

      "should have access to original question in the state" in { s =>
        interaction(s.questioner, s.responder) {

          withDefaultWalletAccess(s, {

            askAndAnswer(s)

            val adState = s.questioner expect state[State.AnswerReceived]
            adState.validStatus.value.signatureValidity shouldBe true
            // A nonce is added by the protocol, ignore the nonce and check the rest.
            compareQuestion(adState.question, testQuestion(EXPIRATION_TIME))

            adState.validStatus.value.timingValidity shouldBe true

            checkStatus(
              s.questioner,
              StatusReport("AnswerReceived", expectedAnswer(CORRECT_ANSWER))
            )
          })
        }
      }
      "should allow ~timing to be None" in { s =>
        interaction(s.questioner, s.responder) {
          withDefaultWalletAccess(s, {

            s.questioner ~ testAskQuestion(None)

            s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))

            s.questioner expect signal[Signal.AnswerGiven]
          })
        }
      }
      "should signal with AnswerDelivered that the question expired" in { s =>
        interaction(s.questioner, s.responder) {

          withDefaultWalletAccess(s, {

            askAndAnswer(s, TIME_EXPIRED, OUT_TIME)

            val adState = s.questioner expect state[State.AnswerReceived]
            adState.validStatus.value.signatureValidity shouldBe true
            compareQuestion(adState.question, testQuestion(TIME_EXPIRED))
            adState.validStatus.value.timingValidity shouldBe false

            checkStatus(
              s.questioner,
              StatusReport("AnswerReceived", expectedAnswer(CORRECT_ANSWER, notExpired = false))
            )
          })
        }
      }
    }
    "negative cases" - {
      "Responder signs incorrect data (changed nonce)" in { s =>
        interaction (s.questioner, s.responder) {
          withDefaultWalletAccess(s, {

            s.questioner ~ testAskQuestion(EXPIRATION_TIME)

            val answerNeeded = s.responder expect signal[Signal.AnswerNeeded]

            val badSigData = Sig(
              SIG,
              buildSignable("safasdfasfdasf").encoded,
              nowDateString
            )

            val sigData2BadSigData = { out: Envelope1[_] =>
              val origMsg = out.msg.asInstanceOf[Msg.Answer]
              val newMsg = Msg.Answer(badSigData)
              out.copy(msg = newMsg)
            }

            s.sys.withReplacer(sigData2BadSigData) {
              s.responder ~ AnswerQuestion(Some(answerNeeded.valid_responses.head))
            }

            val answer = s.questioner expect signal[Signal.AnswerGiven]
            answer.valid_signature shouldBe false
            answer.valid_answer shouldBe false
            answer.not_expired shouldBe true
            checkStatus(
              s.questioner,
              StatusReport("AnswerReceived", expectedAnswer("", validAnswer = false, validSig = false))
            )
          })
        }
      }
      "the answer is not one of the valid answer given" in { s => // not working for the first time yet
        interaction(s.questioner, s.responder) {

          withDefaultWalletAccess(s, {

            s.questioner ~ testAskQuestion(None)

            s.responder expect signal [Signal.AnswerNeeded]

            s.responder ~ AnswerQuestion(Some("Hi Ho"))

            val v = s.responder expect signal [Signal.ProblemReport]
            v.description.code shouldBe ProblemReportCodes.invalidAnswer
            v.description.en should not be None
          })
        }
      }
    }

    "Unexpected control message produces problem report and do not change state" in { s =>
      interaction (s.questioner, s.responder) {

        s.questioner ~ testAskQuestion(EXPIRATION_TIME)

        s.responder expect signal [Signal.AnswerNeeded]

        s.responder walletAccess MockableWalletAccess()

        s.questioner walletAccess MockableWalletAccess()

        s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))

        s.responder.state shouldBe a[State.AnswerSent]
        checkStatus(s.responder, StatusReport("AnswerSent"))

        // Unexpected message
        s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))
        val pr = s.responder expect signal [Signal.ProblemReport]
        pr.description.code shouldBe ProblemReportCodes.unexpectedMessage

        // state didn't change
        s.responder.state shouldBe a[State.AnswerSent]
        checkStatus(s.responder, StatusReport("AnswerSent"))


        s.questioner.state shouldBe a[State.AnswerReceived]
        val result = s.questioner expect signal [Signal.AnswerGiven]
        result.valid_signature shouldBe true
        checkStatus(
          s.questioner,
          StatusReport("AnswerReceived", expectedAnswer(CORRECT_ANSWER))
        )
      }
    }

    "function unit tests" - {
      "createNonce" - {
        "two calls must not be the same value" in { _ =>
          createNonce() should not be createNonce()
        }
      }
      "now" - {
        "two calls should increase in value" in { _ =>
          val v1 = now
          Thread.sleep(1)
          val v2 = now
          assert(v2 > v1)
        }
      }
      "isNotExpired" - {
        "None is false" in { _ =>
          isNotExpired(None) shouldBe false
        }
        "future time is true" in { _ =>
          val expiration = longToDateString(now+10000)
          isNotExpired(Some(expiration)) shouldBe true
        }
        "past time is false" in { _ =>
          val expiration = longToDateString(now-10000)
          isNotExpired(Some(expiration)) shouldBe false
        }
        "bad datetime should throw" in { _ =>
          intercept[IllegalArgumentException] {
            isNotExpired(Some("SDFEFINSDF"))
          }
        }
      }

      "buildSignableHash" - {
        "pin result" in { _ =>
          // This test should pin results of the result to a particular result. This should help us detect that a change
          // has the effect of changing this deterministic result.
          CommittedAnswerProtocol.buildSignable(
            "fa910a8f-d703-4334-a3e4-c4bb745844b3").encoded shouldBe
              "ZmE5MTBhOGYtZDcwMy00MzM0LWEzZTQtYzRiYjc0NTg0NGIz"
        }
        "handle null inputs" in { _ =>
          CommittedAnswerProtocol.buildSignable(null) shouldBe a[Signable]
        }
      }
    }
  }

  def expectedAnswer(answer: String,
                     validAnswer: Boolean = true,
                     validSig: Boolean = true,
                     notExpired: Boolean = true): Some[AnswerGiven] = {
    Some(
      AnswerGiven(
        answer,
        valid_answer = validAnswer,
        valid_signature = validSig,
        not_expired = notExpired
      )
    )
  }

  def compareQuestion(q1: Msg.Question, q2: Msg.Question): Assertion = {
    val normalizedResponses  = q1.valid_responses
      .zip(q2.valid_responses)
      .map{ x =>
        (x._1.copy(nonce = ""), x._2.copy(nonce = ""))
      }
      .unzip
    assert(
      q1.copy(valid_responses = normalizedResponses._1) == q2.copy(valid_responses = normalizedResponses._2)
    )
  }

  def withDefaultWalletAccess(s: Scenario, f: => Unit): Unit = {
    s.responder walletAccess MockableWalletAccess()
    s.questioner walletAccess MockableWalletAccess()
    f
  }

  def askAndAnswer(s: Scenario,
                   ex: Option[BaseTiming] = EXPIRATION_TIME,
                   out: Option[BaseTiming] = OUT_TIME): Unit = {

    s.questioner ~ testAskQuestion(ex)
    s.questioner expect state [State.QuestionSent]
    s.responder expect signal [Signal.AnswerNeeded]
    s.responder expect state [State.QuestionReceived]

    s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))
    s.responder expect state [State.AnswerSent]
    s.questioner expect state [State.AnswerReceived]
  }

  override val containerNames: Set[ContainerName] = Set(TestingVars.QUESTIONER, TestingVars.RESPONDER)

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def appConfig: AppConfig = TestExecutionContextProvider.testAppConfig

}

object TestingVars {
  val QUESTIONER = "questioner"
  val RESPONDER = "responder"
  val QUESTION_TEXT = "ask a question"
  val QUESTION_DETAIL = Option("Some Context to question")
  val CORRECT_ANSWER = "answer1"
  val SIG: String = Base64Util.getBase64Encoded(CORRECT_ANSWER.getBytes())
  val VALID_RESPONSES: Vector[QuestionResponse] = Vector(
    QuestionResponse(CORRECT_ANSWER, "123"),
    QuestionResponse("reject", "456")
  )
  val EXPIRATION_TIME = Option(BaseTiming(expires_time = Some("2118-12-13T17:29:06+0000")))
  val TIME_EXPIRED = Option(BaseTiming(expires_time = Some("2017-12-13T17:29:06+0000")))
  val OUT_TIME = Option(BaseTiming(out_time = Some("2018-12-13T17:29:34+0000")))
  val TEST_SIGNATURE_DATA: Sig = Sig(SIG, "base64 of response", nowDateString)

  def testAskQuestion(time: Option[BaseTiming]): AskQuestion = {
    AskQuestion(
      QUESTION_TEXT,
      QUESTION_DETAIL,
      responsesToStrings(VALID_RESPONSES),
      time.flatMap(_.expires_time)
    )
  }

  def testQuestion(time: Option[BaseTiming]): Msg.Question =
    Msg.Question(QUESTION_TEXT, QUESTION_DETAIL, VALID_RESPONSES, time)

  def testAnswer(time: Option[BaseTiming], sigData: Sig = TEST_SIGNATURE_DATA): Msg.Answer =
    Msg.Answer(sigData)
}
