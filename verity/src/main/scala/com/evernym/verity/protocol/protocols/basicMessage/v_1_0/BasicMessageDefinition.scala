package com.evernym.verity.protocol.protocols.basicMessage.v_1_0

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{AccessRight, AccessSign, AccessVerify, MsgFamily, ParameterName, Parameters, ProtocolContextApi, ProtocolDefinition, Scope}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Role.{Participator}

object BasicMessageDefinition extends ProtocolDefinition[BasicMessage, Role, Msg, Event, State, String] {
  val msgFamily: MsgFamily = BasicMessageMsgFamily

  override def createInitMsg(p: Parameters): Control = Ctl.Init(p.paramValueRequired(SELF_ID), p.paramValueRequired(OTHER_ID))

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID)

  override val roles: Set[Role] = Set(Participator)

  override val requiredAccess: Set[AccessRight] = Set()

  override def create(context: ProtocolContextApi[BasicMessage, Role, Msg, Event, State, String]): BasicMessage = {
    new BasicMessage(context)
  }

  def initialState: State = State.Uninitialized()

  override def scope: Scope.ProtocolScope = Scope.Relationship // Should be tied to a given Relationship
}
