package com.evernym.verity.vault

import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.actor.wallet.{CreateDID, CreateNewKey, GetVerKey, NewKeyCreated, StoreTheirKey, TheirKeyStored}
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess.KEY_ED25519
import com.evernym.verity.testkit.{BasicSpecWithIndyCleanup, HasTestWalletAPI}


class WalletAPISpec
  extends BasicSpecWithIndyCleanup
    with HasTestWalletAPI
    with CommonSpecUtil {

  lazy val aliceWap: WalletAPIParam = createWallet("alice", testWalletAPI)
  lazy val bobWap: WalletAPIParam = createWallet("bob", testWalletAPI)

  var aliceKey: NewKeyCreated = _
  var bobKey: NewKeyCreated = _

  val msg: String = "hi there"

  "Wallet API" - {
    "when asked to create new key in alice's wallet" - {
      "should create key successfully" in {
        aliceKey = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey())(aliceWap)
        aliceKey shouldBe a[NewKeyCreated]
      }
    }

    "when asked to create new key in bob's wallet" - {
      "should create key successfully" in {
        bobKey = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey())(bobWap)
        bobKey shouldBe a[NewKeyCreated]
      }
    }

    "when asked to store bob's key in alice's wallet" - {
      "should store their key successfully" in {
        val response = testWalletAPI.executeSync[TheirKeyStored](StoreTheirKey(bobKey.did, bobKey.verKey))(aliceWap)
        response shouldBe a[TheirKeyStored]
        val responseVerKey = testWalletAPI.executeSync[VerKey](GetVerKey(bobKey.did))(aliceWap)
        responseVerKey shouldBe bobKey.verKey
      }
    }

    "when asked to store alice's key in bob's wallet" - {
      "should store their key successfully" in {
        val response = testWalletAPI.executeSync[TheirKeyStored](StoreTheirKey(aliceKey.did, aliceKey.verKey))(bobWap)
        response shouldBe a[TheirKeyStored]
        val responseVerKey = testWalletAPI.executeSync[VerKey](GetVerKey(aliceKey.did))(bobWap)
        responseVerKey shouldBe aliceKey.verKey
      }
    }

    "when asked to store other's key with fully qualified DID" - {
      "should store their key successfully" in {
        val response = testWalletAPI.executeSync[TheirKeyStored](StoreTheirKey("did:sov:NcysrVCeLU1WNdJdLYxU6g", "CnToPx3rPHNaXkMMtdPTnsK45pSHvP1e4BzNrk3oSVgr"))(bobWap)
        response shouldBe a[TheirKeyStored]

      }
    }
    "when asked to create new DID" - {
      "should be successful" in {
        val testWap: WalletAPIParam = createWallet("test", testWalletAPI)
        val response = testWalletAPI.executeSync[NewKeyCreated](CreateDID(KEY_ED25519))(testWap)
        response shouldBe a[NewKeyCreated]
      }
    }
  }

}
