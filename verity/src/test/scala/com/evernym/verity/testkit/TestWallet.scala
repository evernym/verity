package com.evernym.verity.testkit

import java.util.UUID

import com.evernym.verity.actor.agent.WalletApiBuilder
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.wallet.{CreateWallet, WalletCreated}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.legacy.services.WalletVerKeyCacheHelper
import com.evernym.verity.protocol.protocols.{HasAgentWallet, HasAppConfig}
import com.evernym.verity.util.TestWalletService
import com.evernym.verity.vault.wallet_api.WalletAPI

class TestWallet(createWallet: Boolean=false) extends HasTestWalletAPI {
  if (createWallet) {
    agentWalletAPI.walletAPI.executeSync[WalletCreated.type](CreateWallet)(wap)
  }
}

trait HasTestWalletAPI extends HasAgentWallet with HasAppConfig {

  def createWallet: Boolean = false

  override lazy val agentWalletId: Option[String] = Option(UUID.randomUUID().toString)

  def appConfig: AppConfig = new TestAppConfig

  lazy val walletAPI: WalletAPI = {
    val walletProvider = new LibIndyWalletProvider(appConfig)
    val walletService = new TestWalletService(appConfig, walletProvider)
    val api = WalletApiBuilder.createWalletAPI(appConfig, walletService, walletProvider)
    if (createWallet) {
      api.executeSync[WalletCreated.type](CreateWallet)(wap)
    }
    api
  }

  lazy val walletVerKeyCacheHelper = new WalletVerKeyCacheHelper(wap, walletAPI, appConfig)
}