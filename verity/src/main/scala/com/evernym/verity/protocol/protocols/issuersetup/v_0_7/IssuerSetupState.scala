package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.engine.Parameters
import com.evernym.verity.protocol.protocols.ledgerPrefixStr

trait Event

sealed trait State
object State {
  case class Uninitialized() extends State

  case class Initialized(parameters: Parameters) extends State

  case class Created(identity: Identity) extends State
  case class Identity(did: DidStr, verKey: VerKeyStr)

  case class WaitingOnEndorser(ledgerPrefix: ledgerPrefixStr, identity: Identity) extends State
}