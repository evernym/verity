package com.evernym.verity.testkit

import akka.actor.ActorSystem
import com.evernym.verity.actor.agent.WalletApiBuilder
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.protocols.{HasAppConfig, HasWallet}
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.util.Util.getNewActorId
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.AgentWalletAPI
import com.evernym.verity.vault.service.ActorWalletService


trait TestWalletHelper extends HasWallet with HasAppConfig {
  val appConfig = new TestAppConfig
  val walletDetail: AgentWalletAPI = {
    val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(appConfig)
    val walletProvider = new LibIndyWalletProvider(appConfig)
    val walletService = new ActorWalletService(ActorSystem())
    implicit lazy val walletAPI: WalletAPI =
      WalletApiBuilder.build(appConfig, TestUtil, walletService, walletProvider, poolConnManager)
    AgentWalletAPI(walletAPI, getNewActorId)
  }
}

class TestWallet(createWallet: Boolean=false) extends TestWalletHelper {
  if (createWallet) {
    walletDetail.walletAPI.createWallet(wap)
  }
}