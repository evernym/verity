package com.evernym.verity.protocol.protocols.issuersetup.v_0_6

import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.engine.Parameters
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.IssuerSetup.Nonce

trait Event

sealed trait State
object State {
  case class Uninitialized() extends State
  case class InitializedWithParams(parameters: Parameters) extends State
  case class Created(data: StateData) extends State
  case class Identity(did: DidStr, verKey: VerKeyStr)
  case class StateData(createNonce: Option[Nonce], identity: Option[Identity])

  // This state is not possible from the code NOW but must be left for already
  // recorded events
  case class Initialized() extends State
  case class Creating(data: StateData) extends State
}