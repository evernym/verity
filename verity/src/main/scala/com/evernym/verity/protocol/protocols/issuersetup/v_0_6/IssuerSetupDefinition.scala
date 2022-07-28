package com.evernym.verity.protocol.protocols.issuersetup.v_0_6

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.msg.Init

object IssuerSetupDefinition extends ProtocolDefinition[IssuerSetup, Role, Msg, Any, State, String] {

  override val msgFamily: MsgFamily = IssuerSetupMsgFamily

  override def scope: Scope.ProtocolScope = Scope.Agent

  override val roles: Set[Role] = Role.roles

  override def supportedMsgs: ProtoReceive = {
    case _: Ctl =>
  }

  override def createInitMsg(params: Parameters): Control = Init(params)
  override val initParamNames: Set[ParameterName] = Set(SELF_ID, MY_ISSUER_DID, DEFAULT_ENDORSER_DID)

  override def create(context: ProtocolContextApi[IssuerSetup, Role, Msg, Any, State, String]):
  Protocol[IssuerSetup, Role, Msg, Any, State, String] = new IssuerSetup()(context)

  override def initialState: State = State.Uninitialized()
}

sealed trait Role
object Role {
  case object Owner extends Role
  val roles: Set[Role] = Set(Owner)
}