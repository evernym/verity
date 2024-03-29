package com.evernym.verity.actor.agent

import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{AgentWalletAPI, WalletAPIParam}


trait HasAgentWallet extends HasWallet {
  def walletAPI: WalletAPI
  lazy val agentWalletAPI: AgentWalletAPI = AgentWalletAPI(walletAPI, agentWalletIdReq)
}

trait HasWallet {
  def agentWalletId: Option[String]
  def agentWalletIdReq: String = agentWalletId.getOrElse(
    throw new RuntimeException("agent wallet id not yet set")
  )
  implicit lazy val wap: WalletAPIParam = WalletAPIParam(agentWalletIdReq)

}


