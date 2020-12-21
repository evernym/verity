package com.evernym.verity.testkit.mock.agent

import java.util.UUID

import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.vault.{WalletAPI, WalletAPIParam}
import com.evernym.verity.vault.service.NonActorWalletService

trait HasWalletHelper extends CommonSpecUtil { this: HasAppConfig =>

  def walletId: String = UUID.randomUUID().toString
  def name: String = walletId

  val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(appConfig)
  val walletProvider = new LibIndyWalletProvider(appConfig)
  val walletService = new NonActorWalletService(appConfig, TestUtil, walletProvider, poolConnManager)
  implicit lazy val walletAPI: WalletAPI = new WalletAPI(walletService, walletProvider)

  implicit lazy val wap = {
    val wap = WalletAPIParam(walletId)
    walletAPI.createWallet(wap)
    wap
  }
}
