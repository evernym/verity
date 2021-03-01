package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0

import java.util.{Base64, UUID}

import com.evernym.verity.actor.agent.TypeFormat
import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.Envelope1
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{SigBlock, Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerProtocol._
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Role.{Questioner, Responder}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.{AnswerGiven, StatusReport}
import com.evernym.verity.protocol.testkit.DSL._
import com.evernym.verity.protocol.testkit.{MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec

import scala.language.{implicitConversions, reflectiveCalls}


class QuestionAnswerProtocolSpec
  extends TestsProtocolsImpl(QuestionAnswerDefinition)
  with BasicFixtureSpec {

  import QuestionAnswerVars._

  lazy val config: AppConfig = new TestAppConfig

  private implicit def EnhancedScenario(s: Scenario) = new {
    val questioner: TestEnvir = s(QUESTIONER)
    val responder: TestEnvir = s(RESPONDER)
  }

  def checkStatus(envir: TestEnvir, expectedStatus: StatusReport): Unit = {
    envir clear signals
    envir ~ GetStatus()
    (envir expect signal [StatusReport]) shouldBe expectedStatus
  }

  "Question Protocol Definition" - {
    "should have two roles" in { _ =>
      QuestionAnswerDefinition.roles.size shouldBe 2
      QuestionAnswerDefinition.roles shouldBe Set(Questioner, Responder)
    }
  }

  "Question Answer Msg" - {
    "Answer Msg" - {
      "produces valid json" in { _ =>
        val t = MockableWalletAccess.randomSig()
        val msg = Msg.Answer(
          "be",
          Some(SigBlock(t.get.signatureResult.toBase64, "SDFSDFSDFSDF", Seq.empty)),
          None
        )
        val threadId = UUID.randomUUID().toString
        val jsonWithType = buildAgentMsg(
          msg,
          UUID.randomUUID().toString,
          threadId,
          QuestionAnswerDefinition,
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
          checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(CORRECT_ANSWER, valid_answer = true, valid_signature = true, not_expired = true))))
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

          checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(CORRECT_ANSWER, valid_answer = true, valid_signature = false, not_expired = true))))

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
            checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(CORRECT_ANSWER, valid_answer = true, valid_signature = true, not_expired = true))))

            s.responder expect state[State.AnswerSent]
            checkStatus(s.responder, StatusReport("AnswerSent", None))
          })
        }
      }

      "should have state of AnswerDelivered with no signature if question didn't specify" in { s =>
        interaction(s.questioner, s.responder) {
          s.questioner ~ testAskQuestion(EXPIRATION_TIME, sigRequired = false)
          s.responder walletAccess MockableWalletAccess()
          s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))
          s.responder expect state[State.AnswerSent]
          checkStatus(s.responder, StatusReport("AnswerSent", None))

          // No SignatureResult ctl sent because question.signatureRequired=false doesn't signal a ValidateSignature
          { s.questioner expect state [State.AnswerReceived] }.validStatus.value.signatureValidity shouldBe true
          checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(CORRECT_ANSWER, valid_answer = true, valid_signature = true, not_expired = true))))
        }
      }

      "should have a state indicating an invalid signature when signature check fails" in { s =>
        interaction(s.questioner, s.responder) {

          s.questioner walletAccess MockableWalletAccess.alwaysVerifyAs(false)
          s.responder walletAccess MockableWalletAccess()

          askAndAnswer(s)

          val signalMsg = s.questioner expect signal [Signal.AnswerGiven]

          signalMsg.valid_signature should not be true
          signalMsg.answer shouldBe testAnswer(OUT_TIME).response

          checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(testAnswer(OUT_TIME).response, valid_answer = true, valid_signature = false, not_expired = true))))
        }
      }

      "should have access to original question in the state" in { s =>
        interaction(s.questioner, s.responder) {

          withDefaultWalletAccess(s, {

            askAndAnswer(s)

            val adState = s.questioner expect state[State.AnswerReceived]
            adState.validStatus.value.signatureValidity shouldBe true
            // A nonce is added by the protocol, ignore the nonce and check the rest.
            adState.question.copy(nonce = "") shouldEqual testQuestion(EXPIRATION_TIME)
            adState.validStatus.value.timingValidity shouldBe true

            checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(CORRECT_ANSWER, valid_answer = true, valid_signature = true, not_expired = true))))
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
            adState.question.copy(nonce = "") shouldEqual testQuestion(TIME_EXPIRED)
            adState.validStatus.value.timingValidity shouldBe false

            checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(CORRECT_ANSWER, valid_answer = true, valid_signature = true, not_expired = false))))
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

            val badSigData = SigBlock(
              SIG,
              buildSignableHash(answerNeeded.text, answerNeeded.valid_responses.head, "safasdfasfdasf").encoded,
              Vector("2RQ6ePCKrLKfkTx6jwsKRgM11agygvQ8w3iXmwC29yNN")
            )

            val sigData2BadSigData = { out: Envelope1[_] =>
              val origMsg = out.msg.asInstanceOf[Msg.Answer]
              val newMsg = Msg.Answer(origMsg.response, Some(badSigData), origMsg.`~timing`)
              out.copy(msg = newMsg)
            }

            s.sys.withReplacer(sigData2BadSigData) {
              s.responder ~ AnswerQuestion(Some(answerNeeded.valid_responses.head))
            }

            val answer = s.questioner expect signal[Signal.AnswerGiven]
            answer.valid_signature shouldBe false
            answer.valid_answer shouldBe true
            answer.not_expired shouldBe true
            checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(answerNeeded.valid_responses.head, valid_answer = true, valid_signature = false, not_expired = true))))
          })
        }
      }
      "should set hasValidSig to false when questioner requests a sig but the answer's sig is None" in { s =>
        interaction(s.questioner, s.responder) {

          withDefaultWalletAccess(s, {

            s.questioner ~ testAskQuestion(EXPIRATION_TIME)

            val sigData2None = { out: Envelope1[_] =>
              val origMsg = out.msg.asInstanceOf[Msg.Answer]
              val newMsg = Msg.Answer(origMsg.response, None, origMsg.`~timing`)
              out.copy(msg = newMsg)
            }

            s.sys.withReplacer(sigData2None) {
              s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))
            }

            val answer = s.questioner expect signal[Signal.AnswerGiven]
            answer.valid_signature should not be true

            val adState = s.questioner expect state[State.AnswerReceived]
            adState.validStatus.value.signatureValidity should not be true

          })
        }
      }
      "the answer is not one of the valid answer given" in { s => // not working for the first time yet
        interaction(s.questioner, s.responder) {

          withDefaultWalletAccess(s, {

            s.questioner ~ testAskQuestion(None)

            s.responder ~ AnswerQuestion(Some("Hi Ho"))

            val v = s.questioner expect signal [Signal.AnswerGiven]
            v.valid_answer shouldBe false
            v.valid_signature shouldBe true
          })
        }
      }
    }
    "function unit tests" - {
      "createNonce" - {
        "two calls must not be the same value" in { _ =>
          QuestionAnswerProtocol.createNonce() should not be QuestionAnswerProtocol.createNonce()
        }
      }
      "now" - {
        "two calls should increase in value" in { _ =>
          val v1 = QuestionAnswerProtocol.now
          Thread.sleep(1)
          val v2 = QuestionAnswerProtocol.now
          assert(v2 > v1)
        }
      }
      "isNotExpired" - {
        "None is false" in { _ =>
          QuestionAnswerProtocol.isNotExpired(None) shouldBe false
        }
        "future time is true" in { _ =>
          val expiration = QuestionAnswerProtocol.longToDateString(QuestionAnswerProtocol.now+10000)
          QuestionAnswerProtocol.isNotExpired(Some(expiration)) shouldBe true
        }
        "past time is false" in { _ =>
          val expiration = QuestionAnswerProtocol.longToDateString(QuestionAnswerProtocol.now-10000)
          QuestionAnswerProtocol.isNotExpired(Some(expiration)) shouldBe false
        }
        "bad datetime should throw" in { _ =>
          intercept[IllegalArgumentException] {
            QuestionAnswerProtocol.isNotExpired(Some("SDFEFINSDF"))
          }
        }
      }

      "buildSignableHash" - {
        "pin result" in { _ =>
          // This test should pin results of the hash to a particular result. This should help us detect that a change
          // has the effect of changing this deterministic result.
          QuestionAnswerProtocol.buildSignableHash(
            "To be or not be",
            "be",
            "fa910a8f-d703-4334-a3e4-c4bb745844b3").encoded shouldBe
            "nE8CQMjC1eefFJ0uGISt797blnPCbG0LYs9mn_hK2fAW9gkfUWMls6gl8Pc5FfwLFtHnBjToTYJNHVYMhiDXcA=="
        }
        "handle null inputs" in { _ =>
          QuestionAnswerProtocol.buildSignableHash(null, null, null) shouldBe a[Signable]
        }
      }
    }
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

  override val containerNames: Set[ContainerName] = Set(QuestionAnswerVars.QUESTIONER, QuestionAnswerVars.RESPONDER)
}

object QuestionAnswerVars extends CommonSpecUtil {
  val QUESTIONER = "questioner"
  val RESPONDER = "responder"
  val QUESTION_TEXT = "ask a question"
  val QUESTION_DETAIL = Option("Some Context to question")
  val CORRECT_ANSWER = "answer1"
  val SIG: String = Base64.getEncoder().encodeToString(CORRECT_ANSWER.getBytes())
  val VALID_RESPONSES: Vector[QuestionResponse] = Vector(
    QuestionResponse(text = CORRECT_ANSWER),
    QuestionResponse(text = "reject")
  )
  val EXPIRATION_TIME = Option(BaseTiming(expires_time = Some("2118-12-13T17:29:06+0000")))
  val TIME_EXPIRED = Option(BaseTiming(expires_time = Some("2017-12-13T17:29:06+0000")))
  val OUT_TIME = Option(BaseTiming(out_time = Some("2018-12-13T17:29:34+0000")))
  val TEST_SIGNATURE_DATA: SigBlock = SigBlock(SIG, "base64 of response", Vector("V1", "V2"))

  def testAskQuestion(time: Option[BaseTiming], sigRequired: Boolean = true): AskQuestion = {
    AskQuestion(
      QUESTION_TEXT,
      QUESTION_DETAIL,
      responsesToStrings(VALID_RESPONSES),
      sigRequired,
      time.flatMap(_.expires_time)
    )
  }

  def testQuestion(time: Option[BaseTiming], sigRequired: Boolean = true): Msg.Question =
    Msg.Question(QUESTION_TEXT, QUESTION_DETAIL, "", signature_required = sigRequired, VALID_RESPONSES, time)

  def testAnswer(time: Option[BaseTiming], sigData: Option[SigBlock] = Some(TEST_SIGNATURE_DATA)): Msg.Answer =
    Msg.Answer(CORRECT_ANSWER, sigData, time)
}
