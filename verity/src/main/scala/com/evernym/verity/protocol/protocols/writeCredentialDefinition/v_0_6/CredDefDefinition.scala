package com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.msg.Init
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AnonCreds, LedgerReadAccess, LedgerWriteAccess}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.State.Undefined

object CredDefDefinition extends CredDefDefinitionTrait

trait CredDefDefinitionTrait extends ProtocolDefinition[WriteCredDef, Role, Msg, Any, CredDefState, String] {
  val msgFamily: MsgFamily = CredDefMsgFamily

  override val roles: Set[Role] = Role.roles

  override def createInitMsg(params: Parameters): Control = Init(params)

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, MY_ISSUER_DID, DEFAULT_ENDORSER_DID)

  override val requiredAccess: Set[AccessRight] = Set(AnonCreds, LedgerReadAccess, LedgerWriteAccess)

  override def supportedMsgs: ProtoReceive = {
    case _: Msg =>
    case _: CredDefControl =>
  }

  override def create(context: ProtocolContextApi[WriteCredDef, Role, Msg, Any, CredDefState, String]): WriteCredDef = {
    new WriteCredDef(context)
  }

  def initialState: CredDefState = Undefined()

  override def scope: Scope.ProtocolScope = Scope.Adhoc // Should run on the Self Relationship
}
