package com.evernym.verity.protocol.protocols.agentprovisioning.common

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.wallet.{CreateNewKey, CreateWallet, NewKeyCreated, StoreTheirKey, TheirKeyStored, WalletCreated}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault._
import com.evernym.verity.vault.wallet_api.WalletAPI

trait AgentWalletSetupProvider {

  def walletAPI: WalletAPI

  //TODO: this method is used by protocols and specs
  //when we change protocols to start using async api, we should change this too
  protected def prepareNewAgentWalletData(forDID: DID, forDIDVerKey: VerKey, walletId: String): NewKeyCreated = {
    val agentWAP = WalletAPIParam(walletId)
    walletAPI.executeSync[WalletCreated.type](CreateWallet)(agentWAP)
    walletAPI.executeSync[TheirKeyStored](StoreTheirKey(forDID, forDIDVerKey))(agentWAP)
    walletAPI.executeSync[NewKeyCreated](CreateNewKey())(agentWAP)
  }
}

case class AskUserAgentCreator(forDID: DID, agentKeyDID: DID, endpointDetailJson: String)

case class AgentCreationCompleted() extends Control with ActorMessage
