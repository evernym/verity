package com.evernym.verity.protocol.protocols.committedAnswer.v_1_0

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AccessSign, AccessVerify}
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.{ParameterName, Parameters, ProtocolDefinition}
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Role.{Questioner, Responder}

object CommittedAnswerDefinition extends ProtocolDefinition[CommittedAnswerProtocol, Role, Msg, Event, State, String] {
  val msgFamily: MsgFamily = CommittedAnswerMsgFamily

  override def createInitMsg(p: Parameters): Control = Ctl.Init(p.paramValueRequired(SELF_ID), p.paramValueRequired(OTHER_ID))

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID)

  override val roles: Set[Role] = Set(Questioner, Responder)

  override val requiredAccess: Set[AccessRight] = Set(AccessSign, AccessVerify)

  override def create(context: ProtocolContextApi[CommittedAnswerProtocol, Role, Msg, Event, State, String]): CommittedAnswerProtocol = {
    new CommittedAnswerProtocol(context)
  }

  def initialState: State = State.Uninitialized()
}

object ProblemReportCodes {
  val invalidAnswer = "invalid-answer"
  val unexpectedMessage = "unexpected-message"
}
