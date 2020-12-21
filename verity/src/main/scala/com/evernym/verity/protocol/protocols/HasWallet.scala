package com.evernym.verity.protocol.protocols

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.vault.{WalletAPIParam, AgentWalletAPI}


trait HasAgentWallet extends HasWallet { this: HasAppConfig =>

  def agentWalletSeed: Option[String]

  def agentActorContext: AgentActorContext

  def agentWalletSeedReq: String = agentWalletSeed.getOrElse(
    throw new RuntimeException("wallet seed not yet set")
  )

  lazy val walletDetail: AgentWalletAPI =
    AgentWalletAPI(agentActorContext.walletAPI, agentWalletSeedReq)

}

trait HasWallet { this: HasAppConfig =>
  def walletDetail: AgentWalletAPI
  implicit lazy val wap: WalletAPIParam = WalletAPIParam(walletDetail.walletId)

}


