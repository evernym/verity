package com.evernym.verity.protocol.protocols.agentprovisioning.common

import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.wallet.{NewKeyCreated, StoreTheirKey}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault._

trait AgentWalletSetupProvider {

  def appConfig: AppConfig
  def walletAPI: WalletAPI
  def walletConfig: WalletConfig

  protected def prepareNewAgentWalletData(forDID: DID, forDIDVerKey: VerKey, walletSeed: String): NewKeyCreated = {
    val agentWalletDetail = AgentWalletAPI(walletAPI, walletSeed)
    val agentWAP = WalletAPIParam(agentWalletDetail.walletId)
    agentWalletDetail.walletAPI.createWallet(agentWAP)
    agentWalletDetail.walletAPI.storeTheirKey(StoreTheirKey(forDID, forDIDVerKey))(agentWAP)
    agentWalletDetail.walletAPI.createNewKey()(agentWAP)
  }
}

case class AskUserAgentCreator(forDID: DID, agentKeyDID: DID, endpointDetailJson: String)

case class AgentCreationCompleted() extends Control with ActorMessageClass
