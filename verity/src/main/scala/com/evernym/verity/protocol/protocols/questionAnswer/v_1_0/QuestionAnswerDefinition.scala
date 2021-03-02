package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AccessSign, AccessVerify}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Role.{Questioner, Responder}

object QuestionAnswerDefinition extends ProtocolDefinition[QuestionAnswerProtocol, Role, Msg, Event, State, String] {
  val msgFamily: MsgFamily = QuestionAnswerMsgFamily

  override def createInitMsg(p: Parameters): Control = Ctl.Init(p.paramValueRequired(SELF_ID), p.paramValueRequired(OTHER_ID))

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID)

  override val roles: Set[Role] = Set(Questioner, Responder)

  override val requiredAccess: Set[AccessRight] = Set(AccessSign, AccessVerify)

  override def create(context: ProtocolContextApi[QuestionAnswerProtocol, Role, Msg, Event, State, String]): QuestionAnswerProtocol = {
    new QuestionAnswerProtocol(context)
  }

  def initialState: State = State.Uninitialized()
}
