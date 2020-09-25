package com.evernym.verity.protocol.protocols

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.vault.{WalletAccessParam, WalletDetail}


trait HasAgentWallet extends HasWallet { this: HasAppConfig =>

  def agentWalletSeed: Option[String]

  def agentActorContext: AgentActorContext

  def agentWalletSeedReq: String = agentWalletSeed.getOrElse(
    throw new RuntimeException("wallet seed not yet set")
  )

  lazy val walletDetail: WalletDetail =
    WalletDetail(agentActorContext.walletAPI, agentActorContext.walletConfig, agentWalletSeedReq)

}

trait HasWallet { this: HasAppConfig =>
  def walletDetail: WalletDetail
  implicit lazy val wap: WalletAccessParam = WalletAccessParam(walletDetail, appConfig, closeAfterUse = false)

}


