package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_5

import com.evernym.verity.actor.agent.AgentDetail
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.engine.Parameters

sealed trait State

object State {

  case class Uninitialized() extends State
  case class Initialized(parameters: Parameters) extends State
  case class RequesterPartiIdSet(parameters: Parameters) extends State
  case class ProvisioningInitiaterPartiIdSet(parameters: Parameters) extends State
  case class PairwiseDIDSet(parameters: Parameters, pdd: AgentDetail) extends State
  case class Connected(parameters: Parameters, pdd: AgentDetail) extends State
  case class Signedup(parameters: Parameters, pdd: AgentDetail) extends State
  case class AgentKeyCreated(did: DidStr, verKey: VerKeyStr) extends State
  case class AgentCreated() extends State
}