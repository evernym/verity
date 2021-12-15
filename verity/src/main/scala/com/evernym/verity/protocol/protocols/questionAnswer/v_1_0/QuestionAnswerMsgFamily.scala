package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0

import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.util2.Base64Encoded
import com.evernym.verity.protocol.Control
import com.evernym.verity.did.didcomm.v1.messages.{AdoptableProblemReport, MsgFamily, ProblemDescription}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{SigBlock, Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl.Init
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerProtocol.Nonce

object QuestionAnswerMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.COMMUNITY_QUALIFIER
  override val name: MsgFamilyName = "questionanswer"
  override val version: MsgFamilyVersion = "1.0"

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "question"      -> classOf[Msg.Question],
    "answer"        -> classOf[Msg.Answer]
  )

  override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "Init"              -> classOf[Init],
    "ask-question"      -> classOf[Ctl.AskQuestion],
    "answer-question"   -> classOf[Ctl.AnswerQuestion],
    "get-status"        -> classOf[Ctl.GetStatus],
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Signal.AnswerGiven]   -> "answer-given",
    classOf[Signal.AnswerNeeded]  -> "answer-needed",
    classOf[Signal.ProblemReport] -> "problem-report",
    classOf[Signal.StatusReport]  -> "status-report",
  )
}
// Sub Types
case class QuestionResponse(text: String)

// Messages
sealed trait Msg extends MsgBase

object Msg {

  case class Question(question_text: String,
                      question_detail: Option[String],
                      nonce: Nonce,
                      signature_required: Boolean,
                      valid_responses: Vector[QuestionResponse],
                      `~timing`: Option[BaseTiming] // Expects field expireTime
                     ) extends Msg


  case class Answer(response: String,
                    `response~sig`: Option[SigBlock],
                    `~timing`: Option[BaseTiming] // Expects field outTime
                   ) extends Msg
}

// Control Messages
sealed trait Ctl extends Control with MsgBase

object Ctl {

  case class Init(selfId: ParameterValue, otherId: ParameterValue) extends Ctl

  case class AskQuestion(text: String,
                         detail: Option[String],
                         valid_responses: Vector[String],
                         signature_required: Boolean,
                         expiration: Option[String]) extends Ctl

  case class AnswerQuestion(response: Option[String]) extends Ctl

  case class GetStatus() extends Ctl
}

// Signal Messages
sealed trait SignalMsg

object Signal {
  case class AnswerNeeded(text: String,
                          details: Option[String],
                          valid_responses: Vector[String],
                          signature_required: Boolean,
                          expiration: Option[String]
                         ) extends SignalMsg

  case class AnswerGiven(answer: Base64Encoded,
                         valid_answer: Boolean,
                         valid_signature: Boolean,
                         not_expired: Boolean
                        ) extends SignalMsg

  case class ProblemReport(description: ProblemDescription) extends AdoptableProblemReport with SignalMsg

  case class StatusReport(status: String, answer: Option[AnswerGiven] = None)

  def buildProblemReport(description: String, code: String): ProblemReport = {
    ProblemReport(
      ProblemDescription(
        Some(description),
        code
      )
    )
  }

}

