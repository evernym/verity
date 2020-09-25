package com.evernym.verity.protocol.protocols.agentprovisioning.common

import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault._

trait AgentWalletSetupProvider {

  def appConfig: AppConfig
  def walletAPI: WalletAPI
  def walletConfig: WalletConfig

  protected def prepareNewAgentWalletData(forDID: DID, forDIDVerKey: VerKey, walletSeed: String): NewKeyCreated = {
    val agentWalletDetail = WalletDetail(walletAPI, walletConfig, walletSeed)
    val agentWAP = WalletAccessParam(agentWalletDetail, appConfig, closeAfterUse = false)
    agentWalletDetail.walletAPI.createAndOpenWallet(agentWAP)
    agentWalletDetail.walletAPI.storeTheirKey(StoreTheirKeyParam(forDID, forDIDVerKey))(agentWAP)
    agentWalletDetail.walletAPI.createNewKey()(agentWAP)
  }
}


case class AskUserAgentCreator(forDID: DID, agentKeyDID: DID, endpointDetailJson: String)

case class AgentCreationCompleted() extends Control with ActorMessageClass