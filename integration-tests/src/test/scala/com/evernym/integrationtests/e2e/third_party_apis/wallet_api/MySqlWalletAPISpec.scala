package com.evernym.integrationtests.e2e.third_party_apis.wallet_api

import com.evernym.verity.vault.wallet_api.base.ClientWalletAPISpecBase
import com.typesafe.config.{Config, ConfigFactory}

import java.io.File

trait MySqlWalletAPISpec {
  this: ClientWalletAPISpecBase =>

  override def walletStorageConfig: Config = {
    ConfigFactory.parseString(
      """
        |verity.lib-indy.wallet.type = "mysql"
        |""".stripMargin
    ).withFallback(
      ConfigFactory.parseFile(new File("verity/src/main/resources/wallet-storage.conf"))
      .resolve()
    )
  }
}
