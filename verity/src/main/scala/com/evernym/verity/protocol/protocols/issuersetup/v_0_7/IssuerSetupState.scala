package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_7.IssuerSetup.Nonce

trait Event

sealed trait State
object State {
  case class Uninitialized() extends State

  case class Initialized() extends State

  case class Created(data: StateData) extends State
  case class Identity(did: DidStr, verKey: VerKeyStr)
  case class StateData(createNonce: Option[Nonce], identity: Option[Identity])

  case class WaitingOnEndorser(ledgerPrefix: String, req: String) extends State

  case class Done(did: String) extends State

  case class Error(error: String) extends State

  // This state is not possible from the code NOW but must be left for already
  // recorded events
  case class Creating(data: StateData) extends State
}