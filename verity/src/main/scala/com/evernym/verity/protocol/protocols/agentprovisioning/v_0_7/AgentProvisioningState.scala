package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7

import com.evernym.verity.protocol.engine.{DID, Parameters, VerKey}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.RequesterKeys

sealed trait AgentProvisioningState

sealed trait AwaitsSponsor {
  def token: Option[TokenDetails]
}

object State {

  case class Uninitialized()                                           extends AgentProvisioningState
  case class Initialized(parameters: Parameters)                       extends AgentProvisioningState
  case class RequestedToProvision()                                    extends AgentProvisioningState
  case class CloudWaitingOnSponsor(requesterKeys: RequesterKeys,
                                   token: Option[TokenDetails] )       extends AgentProvisioningState with AwaitsSponsor
  case class EdgeCreationWaitingOnSponsor(requesterVk: VerKey,
                                          token: Option[TokenDetails]) extends AgentProvisioningState with AwaitsSponsor
  case class Provisioning()                                            extends AgentProvisioningState
  case class AgentCreated(selfDID: DID, agentVerKey: VerKey)           extends AgentProvisioningState
  case class FailedAgentCreation(err: String)                          extends AgentProvisioningState
}