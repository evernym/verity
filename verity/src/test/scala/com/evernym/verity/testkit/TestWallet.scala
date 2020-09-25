package com.evernym.verity.testkit

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.libindy.LibIndyWalletProvider
import com.evernym.verity.protocol.protocols.{HasAppConfig, HasWallet}
import com.evernym.verity.util.Util
import com.evernym.verity.vault.WalletUtil._
import com.evernym.verity.util.Util.getNewActorId
import com.evernym.verity.vault.{WalletAPI, WalletConfig, WalletDetail}


trait TestWalletHelper extends HasWallet with HasAppConfig {
  val appConfig = new TestAppConfig
  val walletDetail: WalletDetail = {
    val walletConfig: WalletConfig = buildWalletConfig(appConfig)

    //NOTE: we are passing the ledger pool manager as null, we may wanna come back to it
    val walletAPI = new WalletAPI(new LibIndyWalletProvider(appConfig), Util, null)
    WalletDetail(walletAPI, walletConfig, getNewActorId)
  }
}

class TestWallet(createWallet: Boolean=false) extends TestWalletHelper {
  if (createWallet) {
    walletDetail.walletAPI.createAndOpenWallet(wap)
  }
}