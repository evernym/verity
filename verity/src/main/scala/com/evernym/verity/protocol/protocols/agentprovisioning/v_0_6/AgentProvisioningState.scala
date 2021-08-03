package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_6

import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.engine.Parameters

sealed trait State

object State {

  case class Uninitialized() extends State
  case class Initialized(parameters: Parameters) extends State
  case class RequesterPartiIdSet() extends State
  case class ProvisionerPartiIdSet() extends State
  case class AgentPairwiseKeyCreated(did: DidStr, verKey: VerKeyStr) extends State
  case class AgentCreated() extends State
}