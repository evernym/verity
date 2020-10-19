package com.evernym.verity.actor.agent.state


/**
 * A base (common state for AgencyAgent, AgencyAgentPairwise, UserAgent and UserAgentPairwise) state
 * trait to be mixed in actual state object
 */
trait AgentStateBase
  extends RelationshipState
    with OptHasSponsorRel
    with AgentWalletSeed
    with AgencyDID
    with ThreadContexts
    with ProtocolInstances

/**
 * A base trait to be mixed in agent common which contains a base state types for AgentCommon
 */
trait HasAgentStateBase
  extends HasRelationshipState
    with HasAgentWalletSeed
    with HasAgencyDID
    with HasThreadContexts
    with HasProtocolInstances {
  type StateType <: AgentStateBase
}