package com.evernym.integrationtests.e2e.third_party_apis.wallet_api

import com.evernym.verity.vault.wallet_api.base.ClientWalletAPISpecBase
import com.typesafe.config.{Config, ConfigFactory}

import java.io.File

trait MySqlWalletAPISpec {
  this: ClientWalletAPISpecBase =>

  override def walletStorageConfig: Config = {
    ConfigFactory.parseString(
      """
        |verity.lib-vdrtools.wallet.type = "mysql"
        |""".stripMargin
    ).withFallback(
      ConfigFactory.parseFile(new File("integration-tests/src/test/resources/common/wallet-storage.conf"))
      .resolve()
    )
  }
}
