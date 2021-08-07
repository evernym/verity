package com.evernym.verity.actor.agent

import com.evernym.verity.actor.wallet.{CreateNewKey, CreateWallet, NewKeyCreated, StoreTheirKey, TheirKeyStored, WalletCreated}
import com.evernym.verity.testkit.AwaitResult
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.wallet_api.WalletAPI

trait AgentWalletSetupProvider extends AwaitResult {

  def walletAPI: WalletAPI

  //TODO: this method is used by protocols and specs
  //when we change protocols to start using async api, we should change this too
  protected def prepareNewAgentWalletData(forDIDPair: com.evernym.verity.did.DidPair, walletId: String): NewKeyCreated = {
    val agentWAP = WalletAPIParam(walletId)
    convertToSyncReq(walletAPI.executeAsync[WalletCreated.type](CreateWallet())(agentWAP))
    convertToSyncReq(walletAPI.executeAsync[TheirKeyStored](StoreTheirKey(forDIDPair.did, forDIDPair.verKey))(agentWAP))
    convertToSyncReq(walletAPI.executeAsync[NewKeyCreated](CreateNewKey())(agentWAP))
  }
}
