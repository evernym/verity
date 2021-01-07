package com.evernym.integrationtests.e2e.third_party_apis.wallet_api

import java.io.File

import com.evernym.verity.vault.wallet_api.base.ClientWalletAPISpecBase
import com.typesafe.config.{Config, ConfigFactory}

trait MySqlWalletAPISpec { this: ClientWalletAPISpecBase =>

  override def walletAPIConfig: Config = ConfigFactory parseString {
    """
      verity.wallet-api = "standard"                   # use "legacy" to test 'legacy wallet api'
      verity.lib-indy.wallet.type = "mysql"
      """
  }

  override def walletStorageConfig: Config =
    ConfigFactory.parseFile(new File("verity/src/main/resources/wallet-storage.conf"))
      .resolve()
}
