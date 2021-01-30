package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0

import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl.Init

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
