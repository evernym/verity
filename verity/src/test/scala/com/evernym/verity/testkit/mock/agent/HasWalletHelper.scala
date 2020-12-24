package com.evernym.verity.testkit.mock.agent

import java.util.UUID

import akka.actor.ActorSystem
import com.evernym.verity.actor.agent.WalletApiBuilder
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.service.ActorWalletService
import com.evernym.verity.vault.wallet_api.WalletAPI

trait HasWalletHelper extends CommonSpecUtil { this: HasAppConfig =>

  def walletId: String = UUID.randomUUID().toString
  def name: String = walletId

  val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(appConfig)
  val walletProvider = new LibIndyWalletProvider(appConfig)
  val walletService = new ActorWalletService(ActorSystem())
  implicit lazy val walletAPI: WalletAPI = WalletApiBuilder.build(appConfig, TestUtil, walletService, walletProvider, poolConnManager)

  implicit lazy val wap = {
    val wap = WalletAPIParam(walletId)
    walletAPI.createWallet(wap)
    wap
  }
}
