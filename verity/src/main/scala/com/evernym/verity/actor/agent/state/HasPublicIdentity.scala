package com.evernym.verity.actor.agent.state

import com.evernym.verity.actor.DidPair

trait PublicIdentity {
  /**
   * publicIdentity is the one which is bootstrapped into ledger
   */
  private var _publicIdentity: Option[DidPair] = None
  def publicIdentity: Option[DidPair] = _publicIdentity
  def setPublicIdentity(ai: DidPair): Unit = _publicIdentity = Option(ai)
}

trait HasPublicIdentity {
  type StateType <: PublicIdentity
  def state: StateType
}
