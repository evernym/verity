package com.evernym.verity.protocol.protocols

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.vault.{WalletAPIParam, AgentWalletAPI}


trait HasAgentWallet extends HasWallet { this: HasAppConfig =>

  def agentWalletId: Option[String]

  def agentActorContext: AgentActorContext

  def agentWalletIdReq: String = agentWalletId.getOrElse(
    throw new RuntimeException("agent wallet id not yet set")
  )

  lazy val walletDetail: AgentWalletAPI =
    AgentWalletAPI(agentActorContext.walletAPI, agentWalletIdReq)

}

trait HasWallet { this: HasAppConfig =>
  def walletDetail: AgentWalletAPI
  implicit lazy val wap: WalletAPIParam = WalletAPIParam(walletDetail.walletId)

}


