package com.evernym.verity.vault

import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.{IndyLedgerPoolConnManager, LibIndyWalletProvider}
import com.evernym.verity.protocol.engine.WalletAccess.KEY_ED25519
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup


class WalletAPISpec extends BasicSpecWithIndyCleanup with CommonSpecUtil {

  lazy val config:AppConfig = new TestAppConfig()
  lazy val poolConnManager: LedgerPoolConnManager =  new IndyLedgerPoolConnManager(config)
  lazy val walletProvider: LibIndyWalletProvider = new LibIndyWalletProvider(config)
  lazy val walletAPI: WalletAPI = new WalletAPI(walletProvider, TestUtil, poolConnManager)

  lazy val aliceWap: WalletAccessParam = createOrOpenWallet("alice", walletAPI)
  lazy val bobWap: WalletAccessParam = createOrOpenWallet("bob", walletAPI)

  var aliceKey: NewKeyCreated = _
  var bobKey: NewKeyCreated = _

  val msg: String = "hi there"

  "Wallet API" - {
    "when asked to create new key in alice's wallet" - {
      "should create key successfully" in {
        aliceKey = walletAPI.createNewKey(CreateNewKeyParam())(aliceWap)
        aliceKey shouldBe a[NewKeyCreated]
      }
    }

    "when asked to create new key in bob's wallet" - {
      "should create key successfully" in {
        bobKey = walletAPI.createNewKey(CreateNewKeyParam())(bobWap)
        bobKey shouldBe a[NewKeyCreated]
      }
    }

    "when asked to store bob's key in alice's wallet" - {
      "should store their key successfully" in {
        val response = walletAPI.storeTheirKey(StoreTheirKeyParam(bobKey.did, bobKey.verKey))(aliceWap)
        response shouldBe a[TheirKeyCreated]
        val responseVerKey = walletAPI.getVerKey(
          GetVerKeyByKeyInfoParam(KeyInfo(Right(GetVerKeyByDIDParam(bobKey.did, getKeyFromPool = false)))))(aliceWap)
        responseVerKey shouldBe bobKey.verKey
      }
    }

    "when asked to store alice's key in bob's wallet" - {
      "should store their key successfully" in {
        val response = walletAPI.storeTheirKey(StoreTheirKeyParam(aliceKey.did, aliceKey.verKey))(bobWap)
        response shouldBe a[TheirKeyCreated]
        val responseVerKey = walletAPI.getVerKey(
          GetVerKeyByKeyInfoParam(KeyInfo(Right(GetVerKeyByDIDParam(aliceKey.did, getKeyFromPool = false)))))(bobWap)
        responseVerKey shouldBe aliceKey.verKey
      }
    }

    "when asked to store other's key with fully qualified DID" - {
      "should store their key successfully" in {
        val response = walletAPI.storeTheirKey(StoreTheirKeyParam("did:sov:NcysrVCeLU1WNdJdLYxU6g", "CnToPx3rPHNaXkMMtdPTnsK45pSHvP1e4BzNrk3oSVgr"))(bobWap)
        response shouldBe a[TheirKeyCreated]

      }
    }
    "when asked to create new DID" - {
      "should open wallet if it was closed" in {

        val testWap: WalletAccessParam = createOrOpenWallet("test", walletAPI)
        walletProvider.close(walletAPI.wallets(testWap.getUniqueKey))
        walletAPI.wallets -= testWap.getUniqueKey

        val response = walletAPI.createDID(KEY_ED25519)(testWap)
        response shouldBe a[NewKeyCreated]
      }
    }
  }

}
