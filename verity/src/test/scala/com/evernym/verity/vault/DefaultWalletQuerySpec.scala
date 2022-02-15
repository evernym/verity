//package com.evernym.verity.vault
//
//import com.evernym.vdrtools.did.Did
//import com.evernym.vdrtools.wallet.Wallet
//import com.evernym.verity.testkit.BasicSpec
//
//
//class DefaultWalletQuerySpec
//  extends BasicSpec {
//
//  val walletName = ""
//  val wallet_key = ""
//
//  val wallet: Wallet = {
//    val config =
//      s"""
//        |{
//        |  "id": "$walletName",
//        |  "storage_type": "default"
//        |}
//        |""".stripMargin
//    val credential =
//      s"""
//        |{
//        |  "key": "$wallet_key",
//        |  "key_derivation_method": "ARGON2I_MOD"
//        |}
//        |""".stripMargin
//    Wallet.openWallet(config, credential).get
//  }
//
//  "Wallet" - {
//    "when queried for a did" - {
//      "should be successful" in {
//        Did.keyForLocalDid(wallet, "").get()
//      }
//    }
//  }
//}
