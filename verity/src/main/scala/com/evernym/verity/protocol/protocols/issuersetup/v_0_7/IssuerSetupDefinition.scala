package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.context.ProtocolContextApi

object IssuerSetupDefinition extends ProtocolDefinition[IssuerSetup, Role, Msg, Event, State, String] {

  override val msgFamily: MsgFamily = IssuerSetupMsgFamily

  override def scope: Scope.ProtocolScope = Scope.Agent

  override val roles: Set[Role] = Role.roles

  override def supportedMsgs: ProtoReceive = {
    case _: IssuerSetupControl =>
  }

  override def createInitMsg(params: Parameters): Control = Initialize(params)
  override val initParamNames: Set[ParameterName] = Set(SELF_ID, MY_ISSUER_DID)

  override def create(context: ProtocolContextApi[IssuerSetup, Role, Msg, Event, State, String]):
  Protocol[IssuerSetup, Role, Msg, Event, State, String] = new IssuerSetup()(context)

  override def initialState: State = State.Uninitialized()
}

sealed trait Role
object Role {
  case object Owner extends Role
  val roles: Set[Role] = Set(Owner)
}