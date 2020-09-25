package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_5

import com.evernym.verity.actor._
import com.evernym.verity.protocol.engine.{DID, Parameters, VerKey}

sealed trait State

object State {

  case class Uninitialized() extends State
  case class Initialized(parameters: Parameters) extends State
  case class RequesterPartiIdSet(parameters: Parameters) extends State
  case class ProvisioningInitiaterPartiIdSet(parameters: Parameters) extends State
  case class PairwiseDIDSet(parameters: Parameters, pdd: AgentDetail) extends State
  case class Connected(parameters: Parameters, pdd: AgentDetail) extends State
  case class Signedup(parameters: Parameters, pdd: AgentDetail) extends State
  case class AgentKeyCreated(did: DID, verKey: VerKey) extends State
  case class AgentCreated() extends State
}