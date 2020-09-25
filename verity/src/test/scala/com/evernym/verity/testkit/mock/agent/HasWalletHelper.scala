package com.evernym.verity.testkit.mock.agent

import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.libindy.{IndyLedgerPoolConnManager, LibIndyWalletProvider}
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.vault.WalletAPI

trait HasWalletHelper extends CommonSpecUtil { this: HasAppConfig =>

  def name: String = scala.util.Random.nextString(5)

  implicit lazy val walletAPI: WalletAPI =
    new WalletAPI(new LibIndyWalletProvider(appConfig), TestUtil, new IndyLedgerPoolConnManager(appConfig))

  implicit lazy val (walletExt, wap) = {
    val key = walletAPI.generateWalletKey()
    val wap = buildWalletAccessParam(name, key)
    val wallet = walletAPI.createAndOpenWallet(wap)
    (wallet, wap)
  }
}
