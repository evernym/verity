package com.evernym.verity.testkit

import com.evernym.verity.actor.agent.WalletApiBuilder
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.protocols.{HasAgentWallet, HasAppConfig}
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.util.TestWalletService
import com.evernym.verity.util.Util.getNewActorId
import com.evernym.verity.vault.wallet_api.WalletAPI


trait TestWalletHelper extends HasAgentWallet with HasAppConfig {
  override def agentWalletId: Option[String] = Option(getNewActorId)
  val appConfig = new TestAppConfig
  val walletAPI: WalletAPI = {
    val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(appConfig)
    val walletProvider = new LibIndyWalletProvider(appConfig)
    val walletService = new TestWalletService(appConfig, TestUtil, walletProvider, poolConnManager)
    WalletApiBuilder.build(appConfig, TestUtil, walletService, walletProvider, poolConnManager)
  }
}

class TestWallet(createWallet: Boolean=false) extends TestWalletHelper {
  if (createWallet) {
    agentWalletAPI.walletAPI.createWallet(wap)
  }
}