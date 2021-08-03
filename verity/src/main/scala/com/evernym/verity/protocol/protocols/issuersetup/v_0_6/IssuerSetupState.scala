package com.evernym.verity.protocol.protocols.issuersetup.v_0_6

import com.evernym.verity.did.{DID, VerKey}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.IssuerSetup.Nonce

trait Event

sealed trait State
object State {
  case class Uninitialized() extends State
  case class Initialized() extends State
  case class Created(data: StateData) extends State
  case class Identity(did: DID, verKey: VerKey)
  case class StateData(createNonce: Option[Nonce], identity: Option[Identity])

  // This state is not possible from the code NOW but must be left for already
  // recorded events
  case class Creating(data: StateData) extends State
}