package com.evernym.verity.protocol.protocols.agentprovisioning.common

import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.wallet.{NewKeyCreated, StoreTheirKey}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault._

trait AgentWalletSetupProvider {

  def walletAPI: WalletAPI

  protected def prepareNewAgentWalletData(forDID: DID, forDIDVerKey: VerKey, walletId: String): NewKeyCreated = {
    val agentWAP = WalletAPIParam(walletId)
    walletAPI.createWallet(agentWAP)
    walletAPI.storeTheirKey(StoreTheirKey(forDID, forDIDVerKey))(agentWAP)
    walletAPI.createNewKey()(agentWAP)
  }
}

case class AskUserAgentCreator(forDID: DID, agentKeyDID: DID, endpointDetailJson: String)

case class AgentCreationCompleted() extends Control with ActorMessageClass
