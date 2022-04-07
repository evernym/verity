package com.evernym.verity.protocol.protocols.writeSchema.v_0_6

import com.evernym.verity.protocol.engine.Parameters

trait Event

sealed trait WriteSchemaState
object State {
  case class Undefined() extends WriteSchemaState
  case class Initialized(parameters: Parameters)                           extends WriteSchemaState
  case class Processing(name: String, version: String, attrs: Seq[String]) extends WriteSchemaState
  case class WaitingOnEndorser(schemaId: String, schemaJson: String)       extends WriteSchemaState
  case class Done(schemaId: String)                                        extends WriteSchemaState
  case class Error(error: String)                                          extends WriteSchemaState
}
