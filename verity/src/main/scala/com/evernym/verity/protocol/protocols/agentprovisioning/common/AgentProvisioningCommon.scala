package com.evernym.verity.protocol.protocols.agentprovisioning.common

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.wallet.{CreateNewKey, CreateWallet, NewKeyCreated, StoreTheirKey, TheirKeyStored, WalletCreated}
import com.evernym.verity.protocol.Control
import com.evernym.verity.vault._
import com.evernym.verity.vault.service.AsyncToSync
import com.evernym.verity.vault.wallet_api.WalletAPI

trait AgentWalletSetupProvider extends AsyncToSync {

  def walletAPI: WalletAPI

  //TODO: this method is used by protocols and specs
  //when we change protocols to start using async api, we should change this too
  protected def prepareNewAgentWalletData(forDIDPair: DidPair, walletId: String): NewKeyCreated = {
    val agentWAP = WalletAPIParam(walletId)
    convertToSyncReq(walletAPI.executeAsync[WalletCreated.type](CreateWallet)(agentWAP))
    convertToSyncReq(walletAPI.executeAsync[TheirKeyStored](StoreTheirKey(forDIDPair.DID, forDIDPair.verKey))(agentWAP))
    convertToSyncReq(walletAPI.executeAsync[NewKeyCreated](CreateNewKey())(agentWAP))
  }
}

case class AskUserAgentCreator(forDIDPair: DidPair, agentKeyDIDPair: DidPair, endpointDetailJson: String)

case class AgentCreationCompleted() extends Control with ActorMessage
