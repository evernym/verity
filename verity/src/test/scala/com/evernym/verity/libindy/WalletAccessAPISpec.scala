package com.evernym.verity.libindy

import com.evernym.verity.libindy.wallet.WalletAccessAPI
import com.evernym.verity.protocol.engine.external_api_access.InvalidSignType
import com.evernym.verity.protocol.engine.{DID, ParticipantId, VerKey}
import com.evernym.verity.testkit.{BasicSpec, HasTestWalletAPI}
import com.evernym.verity.util.ParticipantUtil

import scala.util.{Failure, Success}

class WalletAccessAPISpec extends BasicSpec with HasTestWalletAPI {

  agentWalletAPI.walletAPI.createWallet(wap)
  val selfParticipantId: ParticipantId = ParticipantUtil.participantId(
    agentWalletAPI.walletAPI.createNewKey().did, None)
  val walletAccess = new WalletAccessAPI(appConfig, agentWalletAPI.walletAPI, selfParticipantId)

  val TEST_MSG: Array[Byte] = "test string".getBytes()
  val INVALID_SIGN_TYPE = "Invalid sign type"

  var did: DID = _
  var verKey: VerKey = _
  var signature: Array[Byte] = _

  "WalletAccessLibindy newDid" - {
    "should succeed" in {
      walletAccess.newDid() match {
        case Success((newDid, newVerKey)) =>
          did = newDid
          verKey = newVerKey
        case Failure(cause) =>
          fail(cause)
      }
    }
  }

  "WalletAccessLibindy sign" - {
    "sign of data should succeed" in {
      walletAccess.sign(TEST_MSG) match {
        case Success(signatureResult) => signature = signatureResult.signature
        case Failure(cause) =>
          fail(cause)
      }
    }

    "sign request with invalid sign type should fail" in {
      walletAccess.sign(TEST_MSG, INVALID_SIGN_TYPE) shouldBe Failure(InvalidSignType(INVALID_SIGN_TYPE))
    }
  }

  "WalletAccessLibindy verify" - {
    "verify request with correct signature should succeed" in {
      walletAccess.verify(selfParticipantId, TEST_MSG, signature) shouldBe Success(true)
    }

    "verify request with modified msg signature should return false" in {
      walletAccess.verify(selfParticipantId, "modified msg".getBytes(), signature) shouldBe Success(false)
    }

    "verify request with invalid data should fail" in {
      walletAccess.verify(selfParticipantId, TEST_MSG, "short sig".getBytes()).isSuccess shouldBe false
    }

    "verify request with invalid VerKey used should return false" in {
      walletAccess.verify(selfParticipantId, TEST_MSG, signature, Some(verKey)) shouldBe Success(false)
    }

    "verify request with invalid sign type should fail" in {
      walletAccess.verify(selfParticipantId, TEST_MSG, signature, None, INVALID_SIGN_TYPE) shouldBe Failure(InvalidSignType(INVALID_SIGN_TYPE))
    }
  }

}
