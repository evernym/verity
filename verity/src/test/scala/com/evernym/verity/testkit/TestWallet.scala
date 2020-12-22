package com.evernym.verity.testkit

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.protocols.{HasAppConfig, HasWallet}
import com.evernym.verity.util.Util
import com.evernym.verity.util.Util.getNewActorId
import com.evernym.verity.vault.service.NonActorWalletService
import com.evernym.verity.vault.{AgentWalletAPI, WalletAPI}


trait TestWalletHelper extends HasWallet with HasAppConfig {
  val appConfig = new TestAppConfig
  val walletDetail: AgentWalletAPI = {

    val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(appConfig)
    val walletProvider = new LibIndyWalletProvider(appConfig)
    val walletService = new NonActorWalletService(appConfig, Util, walletProvider, poolConnManager)
    val walletAPI = new WalletAPI(walletService, walletProvider)
    AgentWalletAPI(walletAPI, getNewActorId)
  }
}

class TestWallet(createWallet: Boolean=false) extends TestWalletHelper {
  if (createWallet) {
    walletDetail.walletAPI.createWallet(wap)
  }
}