package com.evernym.verity.protocol.protocols.committedAnswer.v_1_0

import com.evernym.verity.Base64Encoded
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.messages.ProblemDescription
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Ctl.Init

object CommittedAnswerMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.COMMUNITY_QUALIFIER
  override val name: MsgFamilyName = "committedanswer"
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
case class QuestionResponse(text: String, nonce: Nonce)

// Messages
sealed trait Msg extends MsgBase

object Msg {

  case class Question(question_text: String,
                      question_detail: Option[String],
                      valid_responses: Vector[QuestionResponse],
                      `@timing`: Option[BaseTiming], // Expects field expireTime
                      external_links: Seq[Link] = Seq.empty
                     ) extends Msg {
    override def validate(): Unit = {
      checkRequired("question_text", question_text)
      checkRequired("valid_responses", valid_responses)
    }
  }

  case class Answer(`response.@sig`: Sig) extends Msg {
    override def validate(): Unit = {
      checkRequired("response.@sig", `response.@sig`)
    }
  }
}

case class Sig(signature: Base64Encoded,
               sig_data: Base64Encoded,
               timestamp: String)
case class Link(test: String, src: String)

// Control Messages
sealed trait Ctl extends Control with MsgBase

object Ctl {

  case class Init(selfId: ParameterValue, otherId: ParameterValue) extends Ctl

  case class AskQuestion(text: String,
                         detail: Option[String],
                         valid_responses: Vector[String],
                         expiration: Option[String]) extends Ctl {
    override def validate(): Unit = {
      checkRequired("text", text)
      checkRequired("valid_responses", valid_responses)
    }
  }

  case class AnswerQuestion(response: Option[String]) extends Ctl

  case class GetStatus() extends Ctl
}

// Signal Messages
sealed trait SignalMsg

object Signal {
  case class AnswerNeeded(text: String,
                          details: Option[String],
                          valid_responses: Vector[String],
                          expiration: Option[String]
                         ) extends SignalMsg

  case class AnswerGiven(answer: String,
                         valid_answer: Boolean,
                         valid_signature: Boolean,
                         not_expired: Boolean
                        ) extends SignalMsg

  case class StatusReport(status: String, answer: Option[AnswerGiven] = None) extends SignalMsg

  case class ProblemReport(description: ProblemDescription) extends SignalMsg

  def buildProblemReport(description: String, code: String): ProblemReport = {
    Signal.ProblemReport(
      ProblemDescription(
        Some(description),
        code
      )
    )
  }
}
