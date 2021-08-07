package com.evernym.verity.libindy

import java.util.UUID

import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.libindy.wallet.{LibIndyWalletExt, LibIndyWalletProvider}
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.vault.{WalletAlreadyExist, WalletAlreadyOpened}
import org.hyperledger.indy.sdk.did.DidResults.CreateAndStoreMyDidResult
import org.hyperledger.indy.sdk.did._


class LibIndyWalletProviderSpec extends BasicSpecWithIndyCleanup with CommonSpecUtil {

  lazy val testAppConfig: AppConfig = new TestAppConfig()
  override def appConfig: AppConfig = testAppConfig

  lazy val lip1 = LibIndyWalletProvider
  lazy val lip2 = LibIndyWalletProvider
  lazy val wn1: String = UUID.randomUUID().toString
  lazy val wn2: String = UUID.randomUUID().toString

  var lip1Wallet: LibIndyWalletExt = _

  var lip1KeyCreatedResult: CreateAndStoreMyDidResult = _
  var walletKey: String = _

  "LibIndyWalletProvider instance one" - {

    "when asked to create new wallet" - {
      "should be able to create it successfully" in {
        walletKey = lip1.generateKeySync()
        lip1.createSync(wn1, walletKey, testWalletConfig)
      }
    }

    "when asked to open created wallet" - {
      "should be able to open the wallet" in {
        lip1Wallet = lip1.openSync(wn1, walletKey, testWalletConfig)
      }
    }

    "when asked to open already opened wallet" - {
      "throws WalletAlreadyOpened exception" in {
        intercept[WalletAlreadyOpened] {
          lip1.openSync(wn1, walletKey, testWalletConfig)
        }
      }
    }

    "when asked to create new key" - {
      "should be able to create it successfully" in {
        val DIDJson = new DidJSONParameters.CreateAndStoreMyDidJSONParameter(null, null, null, null)
        lip1KeyCreatedResult = Did.createAndStoreMyDid(lip1Wallet.wallet, DIDJson.toJson).get
      }
    }

    "when asked to get ver key back" - {
      "should be able to get it successfully" in {
        val vk = Did.keyForLocalDid(lip1Wallet.wallet, lip1KeyCreatedResult.getDid).get
        vk shouldBe lip1KeyCreatedResult.getVerkey
      }
    }

    "when asked to store their key" - {
      "should be able to store it successfully" in {
        val did1 = generateNewDid()
        val DIDJson = s"""{\"did\":\"${did1.did}\",\"verkey\":\"${did1.verKey}\"}"""
        Did.storeTheirDid(lip1Wallet.wallet, DIDJson)
      }
    }
  }

  "LibIndyWalletProvider instance two" - {
    "when asked to create existing new wallet" - {
      "should fail " in {
        intercept[WalletAlreadyExist] {
          lip2.createSync(wn1, walletKey, testWalletConfig)
        }
      }
    }
  }

  "LibIndyWalletProvider instance one" - {
    "when asked to get ver key again" - {
      "should be able to get it successfully" in {
        val vk = Did.keyForLocalDid(lip1Wallet.wallet, lip1KeyCreatedResult.getDid).get
        vk shouldBe lip1KeyCreatedResult.getVerkey
      }
    }
  }

}
