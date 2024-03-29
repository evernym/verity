package com.evernym.verity.protocol.protocols.writeSchema.v_0_6

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.msg.Init
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.State.Undefined

object WriteSchemaDefinition extends WriteSchemaDefTrait

trait WriteSchemaDefTrait extends ProtocolDefinition[WriteSchema, Role, Msg, Any, WriteSchemaState, String] {

  val msgFamily: MsgFamily = WriteSchemaMsgFamily

  override val roles: Set[Role] = Role.roles

  override def createInitMsg(params: Parameters): Control = Init(params)

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, MY_ISSUER_DID, DEFAULT_ENDORSER_DID)

  override def supportedMsgs: ProtoReceive = {
    case _: SchemaControl =>
  }

  override def create(context: ProtocolContextApi[WriteSchema, Role, Msg, Any, WriteSchemaState, String]): WriteSchema = {
    new WriteSchema(context)
  }

  def initialState: WriteSchemaState = Undefined()

  override def scope: Scope.ProtocolScope = Scope.Adhoc // Should run on the Self Relationship
}
