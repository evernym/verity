package com.evernym.verity.testkit.mock.agent

import java.util.UUID

import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.util.TestWalletService
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.wallet_api.StandardWalletAPI

trait HasWalletHelper extends CommonSpecUtil { this: HasAppConfig =>

  def walletId: String = UUID.randomUUID().toString
  def name: String = walletId

  val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(appConfig)
  val walletProvider = new LibIndyWalletProvider(appConfig)
  val walletService = new TestWalletService(appConfig, TestUtil, walletProvider, poolConnManager)
  val walletAPI = new StandardWalletAPI(walletService, walletProvider)

  implicit lazy val wap: WalletAPIParam = {
    val wap = WalletAPIParam(walletId)
    walletAPI.createWallet(wap)
    wap
  }
}
