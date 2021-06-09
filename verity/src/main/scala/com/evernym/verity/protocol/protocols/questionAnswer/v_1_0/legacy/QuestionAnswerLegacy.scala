package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.legacy

import com.evernym.verity.protocol.engine.Protocol
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.CommonProtoTypes.SigBlock
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerProtocol._
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Role._
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.State.AnswerValidity
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0._
import com.evernym.verity.util.Base64Util.getBase64MultiDecoded

import scala.util.{Failure, Success}

trait QuestionAnswerLegacy
  extends Protocol[QuestionAnswerProtocol, Role, Msg, Event, State, String]
    with ProtocolHelpers[QuestionAnswerProtocol, Role, Msg, Event, State, String] {

  def legacyApply: ApplyEvent = {
    case (_: State.Initialized, r, e: QuestionUsed) =>
      r.selfRole_! match {
        case Questioner => State.QuestionSentLegacy(buildQuestion(e))
        case Responder => State.QuestionReceivedLegacy(buildQuestion(e))
      }
    case (s                      , _ , Error(code, comment)) => recordErrorLegacy(s, code, comment)

    // Questioner Evt
    case (s: State.QuestionSentLegacy, _, e: AnswerUsed) =>
      State.AnswerReceivedLegacy(s.question, e.response, None, e.received, None)
    case (s: State.QuestionSentLegacy, _, e: SignedAnswerUsed) =>
      State.AnswerReceivedLegacy(s.question, e.response, Some(evtToSigBlock(e)), e.received, None)
    case (s: State.AnswerReceivedLegacy, _, e: Validity) =>
      s.copy(validStatus = Some(AnswerValidity(e.answerValidity, e.signatureValidity, e.timingValidity)))

    // Responder Evt
    case (State.QuestionReceivedLegacy(q), _, e: AnswerUsed) =>
      State.AnswerSentLegacy(q, e.response, None)
    case (State.QuestionReceivedLegacy(q), _, e: SignedAnswerUsed) =>
      State.AnswerSentLegacy(q, e.response, Some(SigBlock(e.signature, e.signatureData, e.signers)))
  }

  def legacyProtoMsg: (State, Option[Role], Msg) ?=> Any = {
    case (s: State.QuestionSentLegacy, _, m: Msg.Answer) => receiveAnswer(s, m)
  }

  def receiveAnswer(s: State.QuestionSentLegacy, m: Msg.Answer): Unit = {
    ctx.apply(answerToEvt(m))

    val notExpired = isNotExpired(
      s.question.`~timing`.flatMap(t => t.expires_time)
    )
    val validResponse = {
      s.question.valid_responses.exists(_.text == m.response)
    }
    if (s.question.signature_required) {
      m.`response~sig` match {
        case Some(x) =>
          val checkData = buildSignableHash(s.question.question_text, m.response, s.question.nonce)
          val receivedSigData = getBase64MultiDecoded(x.sig_data)
          if (!(receivedSigData sameElements checkData.bytes)) {
            answerValidatedLeg(m.response, validResponse, validSignature = false, notExpired)
          }
          else {
            ctx.wallet.verify(
              ctx.getRoster.participantIdForRole_!(Role.Responder),
              receivedSigData,
              getBase64MultiDecoded(x.signature),
              x.signers.headOption
            ) {
              case Success(sigVerifResult) =>
                answerValidatedLeg(m.response, validResponse, sigVerifResult.verified, notExpired)
              case Failure(ex) =>
                ctx.logger.warn(s"Unable to verify signature - ${ex.getMessage}")
                answerValidatedLeg(m.response, validResponse, validSignature = false, notExpired)
            }
          }
        case None =>
          answerValidatedLeg(m.response, validResponse, validSignature = false, notExpired)
      }
    } else {
      answerValidatedLeg(m.response, validResponse, validSignature = true, notExpired)
    }
  }

  def answerValidatedLeg(answer: String, validResponse: Boolean, validSignature: Boolean, notExpired: Boolean): Unit = {
    ctx.apply(Validity(validResponse, validSignature, notExpired))
    ctx.signal(Signal.AnswerGiven(answer, validResponse, validSignature, notExpired))
  }

  def recordErrorLegacy(s: State, code: Int, comment: String): State.ErrorLegacy = {
    s match {
      case _: State.Uninitialized => State.ErrorLegacy(code, comment)
      case _: State.Initialized => State.ErrorLegacy(code, comment)
      case State.QuestionSentLegacy(q) => State.ErrorLegacy(code, comment, question = Some(q))
      case State.AnswerReceivedLegacy(q, r, s, t, v) =>
        State.ErrorLegacy(code, comment, Some(q), Some(r), s, Some(t), v)
      case State.QuestionReceivedLegacy(q) => State.ErrorLegacy(code, comment, question = Some(q))
      case State.AnswerSentLegacy(q, r, s) =>
        State.ErrorLegacy(code, comment, question = Some(q), response = Some(r), signature = s)
      case s => State.ErrorLegacy(code, comment + s" (From an unknown state: ${s.getClass.getSimpleName})")
    }
  }
}
