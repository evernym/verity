package com.evernym.verity.protocol.protocols.issuersetup.v_0_6

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.metrics.MetricsWriterExtensionImpl
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.asyncapi.{AccessNewDid, AccessRight}
import com.evernym.verity.protocol.engine._

object IssuerSetupDefinition extends ProtocolDefinition[IssuerSetup, Role, Msg, Event, State, String] {

  override val msgFamily: MsgFamily = IssuerSetupMsgFamily

  override def scope: Scope.ProtocolScope = Scope.Agent

  override val requiredAccess: Set[AccessRight] = Set(AccessNewDid)

  override val roles: Set[Role] = Role.roles

  override def supportedMsgs: ProtoReceive = {
    case _: Ctl =>
  }

  override def createInitMsg(params: Parameters): Control = InitMsg(params.paramValueRequired(SELF_ID))
  override def initParamNames: Set[ParameterName] = Set(SELF_ID)

  override def create(context: ProtocolContextApi[IssuerSetup, Role, Msg, Event, State, String], mw: MetricsWriterExtensionImpl):
  Protocol[IssuerSetup, Role, Msg, Event, State, String] = new IssuerSetup()(context)

  override def initialState: State = State.Uninitialized()
}

sealed trait Role
object Role {
  case object Owner extends Role
  val roles: Set[Role] = Set(Owner)
}