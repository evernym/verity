package com.evernym.verity.protocol.protocols.connections.v_1_0

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.Scope
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.{ParameterName, Parameters, ProtocolDefinition}

object ConnectionsDef extends ProtocolDefinition[Connections, Role, Msg, Event, State, String] {
  val msgFamily: MsgFamily = ConnectionsMsgFamily

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID, DATA_RETENTION_POLICY)

  override val roles: Set[Role] = Set(Role.Inviter, Role.Invitee)

  override def createInitMsg(p: Parameters): Control = Ctl.Init(p)

  override def create(context: ProtocolContextApi[Connections, Role, Msg, Event, State, String]): Connections = {
    new Connections(context)
  }

  def initialState: State = State.Uninitialized()

  override def scope: Scope.ProtocolScope = Scope.RelProvisioning
}