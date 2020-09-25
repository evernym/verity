package com.evernym.verity.actor.agent.state

/**
 * A trait to be mixed in actual state object
 *
 * contains wallet seed
 */
trait AgentWalletSeed {
  private var _agentWalletSeed: Option[String] = None
  def agentWalletSeed: Option[String] = _agentWalletSeed
  def setAgentWalletSeed(seed: String): Unit = _agentWalletSeed = Option(seed)
}


trait HasAgentWalletSeed {
  type StateType <: AgentWalletSeed
  def state: StateType
}
