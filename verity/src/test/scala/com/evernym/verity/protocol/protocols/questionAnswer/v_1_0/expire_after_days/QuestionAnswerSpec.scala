package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.expire_after_days

import com.evernym.verity.actor.agent.TypeFormat
import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.constants.InitParamConstants.DATA_RETENTION_POLICY
import com.evernym.verity.protocol.engine.Envelope1
import com.evernym.verity.protocol.protocols.CommonProtoTypes.SigBlock
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerProtocol._
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Role.{Questioner, Responder}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.{AnswerGiven, StatusReport}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0._
import com.evernym.verity.protocol.testkit.DSL._
import com.evernym.verity.protocol.testkit.MockableWalletAccess

import java.util.UUID
import scala.language.{implicitConversions, reflectiveCalls}


class QuestionAnswerSpec
  extends QuestionAnswerBaseSpec {

  import QuestionAnswerVars._

  override val defaultInitParams = Map(
    DATA_RETENTION_POLICY -> "30 day"
  )

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

          s.checkTotalSegments(0)
          s.questioner ~ testAskQuestion(EXPIRATION_TIME)

          s.responder expect signal [Signal.AnswerNeeded]

          s.questioner.state shouldBe a[State.QuestionSent]
          checkStatus(s.questioner, StatusReport("QuestionSent"))

          s.responder.state shouldBe a[State.QuestionReceived]
          checkStatus(s.responder, StatusReport("QuestionReceived"))
          s.checkTotalSegments(2, waitMillisBeforeCheck = 200)
        }
      }
    }

    "when Responder sends answer" - {
      "Questioner should accept answer" in { s =>
        interaction (s.questioner, s.responder) {

          s.checkTotalSegments(0)
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

          s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
        }
      }
    }

    "when Questioner verifies response" - {
      "should have state of AnswerReceived with hasValidSig equal to false" in { s =>
        interaction (s.questioner, s.responder) {

          s.checkTotalSegments(0)

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

          s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
        }
      }

      "should have state of AnswerDelivered with valid response" in { s =>
        interaction (s.questioner, s.responder) {
          s.checkTotalSegments(0)

          withDefaultWalletAccess(s, {
            askAndAnswer(s)

            val answerDelivered = s.questioner expect state[State.AnswerReceived]
            answerDelivered.validStatus.value.signatureValidity shouldBe true
            checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(CORRECT_ANSWER, valid_answer = true, valid_signature = true, not_expired = true))))

            s.responder expect state[State.AnswerSent]
            checkStatus(s.responder, StatusReport("AnswerSent", None))
          })
          s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
        }
      }

      "should have state of AnswerDelivered with no signature if question didn't specify" in { s =>
        interaction(s.questioner, s.responder) {
          s.checkTotalSegments(0)
          s.questioner ~ testAskQuestion(EXPIRATION_TIME, sigRequired = false)
          s.responder walletAccess MockableWalletAccess()
          s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))
          s.responder expect state[State.AnswerSent]
          checkStatus(s.responder, StatusReport("AnswerSent", None))

          // No SignatureResult ctl sent because question.signatureRequired=false doesn't signal a ValidateSignature
          { s.questioner expect state [State.AnswerReceived] }.validStatus.value.signatureValidity shouldBe true
          checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(CORRECT_ANSWER, valid_answer = true, valid_signature = true, not_expired = true))))
          s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
        }
      }

      "should have a state indicating an invalid signature when signature check fails" in { s =>
        interaction(s.questioner, s.responder) {
          s.checkTotalSegments(0)
          s.questioner walletAccess MockableWalletAccess.alwaysVerifyAs(false)
          s.responder walletAccess MockableWalletAccess()

          askAndAnswer(s)

          val signalMsg = s.questioner expect signal [Signal.AnswerGiven]

          signalMsg.valid_signature should not be true
          signalMsg.answer shouldBe testAnswer(OUT_TIME).response

          checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(testAnswer(OUT_TIME).response, valid_answer = true, valid_signature = false, not_expired = true))))
          s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
        }
      }

      "should have access to original question in the state" in { s =>
        interaction(s.questioner, s.responder) {
          s.checkTotalSegments(0)
          withDefaultWalletAccess(s, {

            askAndAnswer(s)

            val adState = s.questioner expect state[State.AnswerReceived]
            adState.validStatus.value.signatureValidity shouldBe true
            // A nonce is added by the protocol, ignore the nonce and check the rest.
            adState.validStatus.value.timingValidity shouldBe true

            checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(CORRECT_ANSWER, valid_answer = true, valid_signature = true, not_expired = true))))
          })
          s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
        }
      }
      "should allow ~timing to be None" in { s =>
        interaction(s.questioner, s.responder) {
          withDefaultWalletAccess(s, {
            s.checkTotalSegments(0)
            s.questioner ~ testAskQuestion(None)

            s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))

            s.questioner expect signal[Signal.AnswerGiven]
            s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
          })
        }
      }
      "should signal with AnswerDelivered that the question expired" in { s =>
        interaction(s.questioner, s.responder) {
          s.checkTotalSegments(0)
          withDefaultWalletAccess(s, {

            askAndAnswer(s, TIME_EXPIRED, OUT_TIME)

            val adState = s.questioner expect state[State.AnswerReceived]
            adState.validStatus.value.signatureValidity shouldBe true
            adState.validStatus.value.timingValidity shouldBe false

            checkStatus(s.questioner, StatusReport("AnswerReceived", Some(AnswerGiven(CORRECT_ANSWER, valid_answer = true, valid_signature = true, not_expired = false))))
          })
          s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
        }
      }
    }
    "negative cases" - {
      "Responder signs incorrect data (changed nonce)" in { s =>
        interaction (s.questioner, s.responder) {
          s.checkTotalSegments(0)
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
          s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
        }
      }
      "should set hasValidSig to false when questioner requests a sig but the answer's sig is None" in { s =>
        interaction(s.questioner, s.responder) {
          s.checkTotalSegments(0)
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
          s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
        }
      }
      "the answer is not one of the valid answer given" in { s => // not working for the first time yet
        interaction(s.questioner, s.responder) {
          s.checkTotalSegments(0)
          withDefaultWalletAccess(s, {

            s.questioner ~ testAskQuestion(None)

            s.responder ~ AnswerQuestion(Some("Hi Ho"))

            val v = s.questioner expect signal [Signal.AnswerGiven]
            v.valid_answer shouldBe false
            v.valid_signature shouldBe true
          })
          s.checkTotalSegments(4, waitMillisBeforeCheck = 200)
        }
      }
    }

    "Data retention expire cases" - {
      "Responder uses AnswerQuestion control message when question segment is expired" - {
        "should send problem report" in { s =>
          interaction (s.questioner, s.responder) {

            s.questioner ~ testAskQuestion(EXPIRATION_TIME)

            s.responder expect signal [Signal.AnswerNeeded]

            s.responder walletAccess MockableWalletAccess()

            s.questioner walletAccess MockableWalletAccess()

            // simulate expiry of the question segment
            val questionReceived = s.responder expect state [State.QuestionReceived]
            s.responder.container_!.removeSegment(questionReceived.id)

            s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))
            val pr = s.responder expect signal [Signal.ProblemReport]
            pr.description.code shouldBe ProblemReportCodes.expiredDataRetention

            s.responder.state shouldBe a[State.QuestionReceived]
            checkStatus(s.responder, StatusReport("QuestionReceived"))

            s.questioner.state shouldBe a[State.QuestionSent]
            checkStatus(s.questioner, StatusReport("QuestionSent"))
          }
        }
      }

      "Questioner receives Answer message when question segment is expired" - {
        "should send problem report message" in { s =>
          interaction (s.questioner, s.responder) {

            s.questioner ~ testAskQuestion(EXPIRATION_TIME)

            s.responder expect signal [Signal.AnswerNeeded]

            s.responder walletAccess MockableWalletAccess()

            s.questioner walletAccess MockableWalletAccess()

            // simulate expiry of the question segment
            val questionSent = s.questioner expect state[State.QuestionSent]
            s.questioner.container_!.removeSegment(questionSent.id)

            s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))

            s.responder.state shouldBe a[State.AnswerSent]
            checkStatus(s.responder, StatusReport("AnswerSent"))

            val pr = s.questioner expect signal [Signal.ProblemReport]
            pr.description.code shouldBe ProblemReportCodes.expiredDataRetention

            s.questioner.state shouldBe a[State.QuestionSent]
            checkStatus(s.questioner, StatusReport("QuestionSent"))
          }
        }
      }

      "Questioner uses GetStatus control message in state AswerReceived with answer segment expired" - {
        "when signature required = false" - {
          "should return status without the answer" in { s =>
            interaction (s.questioner, s.responder) {

              s.questioner ~ testAskQuestion(EXPIRATION_TIME, sigRequired = false)

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

              // simulate expiry of the question segment
              val answerReceived = s.questioner expect state[State.AnswerReceived]
              s.questioner.container_!.removeSegment(answerReceived.id)

              checkStatus(s.questioner, StatusReport("AnswerReceived", None))
            }
          }
        }
        "signature required = true" - {
          "should return status without the answer" in { s =>
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

              // simulate expiry of the question segment
              val answerReceived = s.questioner expect state[State.AnswerReceived]
              s.questioner.container_!.removeSegment(answerReceived.id)

              checkStatus(s.questioner, StatusReport("AnswerReceived", None))
            }
          }

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

  override val containerNames: Set[ContainerName] = Set(QuestionAnswerVars.QUESTIONER, QuestionAnswerVars.RESPONDER)
}
