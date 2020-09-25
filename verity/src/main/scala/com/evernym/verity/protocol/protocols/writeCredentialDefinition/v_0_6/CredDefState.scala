package com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6

import com.evernym.verity.protocol.engine.Parameters

sealed trait CredDefState
object State {
  case class Undefined()                                                extends CredDefState
  case class Initialized(parameters: Parameters)                        extends CredDefState
  case class Processing(name: String, schemaId: String)                 extends CredDefState
  case class WaitingOnEndorser(credDefId: String, credDefJson: String)  extends CredDefState
  case class Done(credDefId: String)                                    extends CredDefState
  case class Error(error: String)                                       extends CredDefState
}
