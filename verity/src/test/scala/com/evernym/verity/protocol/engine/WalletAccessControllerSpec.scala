package com.evernym.verity.protocol.engine

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.wallet._
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.libindy.wallet.WalletAccessAPI
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.engine.external_api_access._
import com.evernym.verity.testkit.{BasicSpec, HasDefaultTestWallet}
import com.evernym.verity.util.ParticipantUtil

import scala.util.Try

class WalletAccessControllerSpec extends BasicSpec {
  "Wallet access controller" - {
    "mixed functions should pass if having correct access rights" in {
      val controller = new WalletAccessController(Set(AccessNewDid, AccessVerify), new TestWalletAccess)
      controller.newDid(){ result => result.isSuccess shouldBe true }
      controller.sign(Array[Byte](1, 2, 3)){ result => result.isSuccess shouldBe false }
      controller.verify("participantId", Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)) { result =>
        result.isSuccess shouldBe true
      }
    }
    "newDid should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessController(Set(AccessNewDid), new TestWalletAccess)
      controllerWithRight.newDid() { result => result.isSuccess shouldBe true }
      val controllerWithoutRight = new WalletAccessController(Set(), new TestWalletAccess)
      controllerWithoutRight.newDid(){ result => result.isSuccess shouldBe false }
    }
    "sign should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessController(Set(AccessSign), new TestWalletAccess)
      controllerWithRight.sign(Array[Byte](1, 2, 3)){ result => result.isSuccess shouldBe true }
      val controllerWithoutRight = new WalletAccessController(Set(), new TestWalletAccess)
      controllerWithoutRight.sign(Array[Byte](1, 2, 3)){ result => result.isSuccess shouldBe false }
    }
    "participantId verify should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessController(Set(AccessVerify), new TestWalletAccess)
      controllerWithRight.verify("participantId", Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)) { result =>
        result.isSuccess shouldBe true
      }
      val controllerWithoutRight = new WalletAccessController(Set(), new TestWalletAccess)
      controllerWithoutRight.verify("participantId", Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)) { result =>
        result.isSuccess shouldBe false
      }
    }

    "verkey verify should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessController(Set(AccessVerify), new TestWalletAccess)
      controllerWithRight.verify(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3), "verkey", SIGN_ED25519_SHA512_SINGLE) { result =>
        result.isSuccess shouldBe true
      }
      val controllerWithoutRight = new WalletAccessController(Set(), new TestWalletAccess)
      controllerWithoutRight.verify(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3), "verkey", SIGN_ED25519_SHA512_SINGLE) { result =>
        result.isSuccess shouldBe false
      }
    }
  }

  class TestWalletAccess extends WalletAccess {
    import com.evernym.verity.protocol.engine.external_api_access.WalletAccess._

    override def newDid(keyType: KeyType)(handler: Try[(DID, VerKey)] => Unit): Unit = handler(Try(("Did", "Verkey")))

    override def verKey(forDID: DID)(handler: Try[VerKey] => Unit): Unit = handler(Try("Verkey"))

    override def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                     (handler: Try[SignatureResult] => Unit): Unit =
      handler(Try(external_api_access.SignatureResult(Array[Byte](1, 2, 3), "VerKey")))

    override def verify(signer: ParticipantId,
                        msg: Array[Byte],
                        sig: Array[Byte],
                        verKeyUsed: Option[VerKey] = None,
                        signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                       (handler: Try[Boolean] => Unit): Unit = handler(Try(true))

    override def verify(msg: Array[Byte],
                        sig: Array[Byte],
                        verKeyUsed: VerKey,
                        signType: SignType)
                       (handler: Try[Boolean] => Unit): Unit = handler(Try(true))


    override def storeTheirDid(did: DID, verKey: VerKey)(handler: Try[TheirKeyStored] => Unit): Unit =
      handler(Try(TheirKeyStored(did, verKey)))

    override def createSchema(issuerDID:  DID,
                              name:  String,
                              version:  String,
                              data:  String)
                             (handler: Try[(String, String)] => Unit): Unit = ???

    override def createCredDef(issuerDID:  DID,
                               schemaJson:  String,
                               tag:  String,
                               sigType:  Option[String],
                               revocationDetails:  Option[String])
                              (handler: Try[(String, String)] => Unit): Unit = ???

    override def createCredOffer(credDefId: String)(handler: Try[String] => Unit): Unit = ???

    override def createCredReq(credDefId: String,
                               proverDID: DID,
                               credDefJson: String,
                               credOfferJson: String)
                              (handler: Try[CreatedCredReq] => Unit): Unit = ???

    override def createCred(credOfferJson: String,
                            credReqJson: String,
                            credValuesJson: String,
                            revRegistryId: String,
                            blobStorageReaderHandle: ParticipantIndex)
                           (handler: Try[String] => Unit): Unit = ???

    override def storeCred(credId: String,
                           credReqMetadataJson: String,
                           credJson: String,
                           credDefJson: String,
                           revRegDefJson: String)
                          (handler: Try[String] => Unit): Unit = ???

    override def credentialsForProofReq(proofRequest: String)
                                       (handler: Try[String] => Unit): Unit = ???

    override def createProof(proofRequest: String,
                             usedCredentials: String,
                             schemas: String,
                             credentialDefs: String,
                             revStates: String)
                            (handler: Try[String] => Unit): Unit = ???

    override def verifyProof(proofRequest: String,
                             proof: String,
                             schemas: String,
                             credentialDefs: String,
                             revocRegDefs: String,
                             revocRegs: String)
                            (handler: Try[Boolean] => Unit): Unit = ???

    override def signRequest(submitterDID: DID,
                             request: String)
                            (handler: Try[LedgerRequest] => Unit): Unit = ???

    override def multiSignRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit = ???
  }
}

object WalletAccessTest extends HasDefaultTestWallet {
  testWalletAPI.executeSync[WalletCreated.type](CreateWallet)
  val newKey: NewKeyCreated = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey())
  val _selfParticipantId: ParticipantId = ParticipantUtil.participantId(newKey.did, None)
  def walletAccess(selfParticipantId: ParticipantId=_selfParticipantId) =
    new WalletAccessAPI(new TestAppConfig, testWalletAPI, selfParticipantId, {}, {})
}

