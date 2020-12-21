package com.evernym.verity.protocol.engine

import com.evernym.verity.actor.wallet.NewKeyCreated
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.libindy.wallet.WalletAccessLibindy
import com.evernym.verity.protocol.engine.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.testkit.{BasicSpec, TestWalletHelper}
import com.evernym.verity.util.ParticipantUtil

import scala.util.Try

class WalletAccessControllerSpec extends BasicSpec {
  "Wallet access controller" - {
    "mixed functions should pass if having correct access rights" in {
      val controller = new WalletAccessController(Set(AccessNewDid, AccessVerify), new TestWalletAccess)
      controller.newDid().isSuccess shouldBe true
      controller.sign(Array[Byte](1, 2, 3)).isSuccess shouldBe false
      controller.verify("participantId", Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)).isSuccess shouldBe true
    }
    "newDid should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessController(Set(AccessNewDid), new TestWalletAccess)
      controllerWithRight.newDid().isSuccess shouldBe true
      val controllerWithoutRight = new WalletAccessController(Set(), new TestWalletAccess)
      controllerWithoutRight.newDid().isSuccess shouldBe false
    }
    "sign should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessController(Set(AccessSign), new TestWalletAccess)
      controllerWithRight.sign(Array[Byte](1, 2, 3)).isSuccess shouldBe true
      val controllerWithoutRight = new WalletAccessController(Set(), new TestWalletAccess)
      controllerWithoutRight.sign(Array[Byte](1, 2, 3)).isSuccess shouldBe false
    }
    "participantId verify should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessController(Set(AccessVerify), new TestWalletAccess)
      controllerWithRight.verify("participantId", Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)).isSuccess shouldBe true
      val controllerWithoutRight = new WalletAccessController(Set(), new TestWalletAccess)
      controllerWithoutRight.verify("participantId", Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)).isSuccess shouldBe false
    }

    "verkey verify should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessController(Set(AccessVerify), new TestWalletAccess)
      controllerWithRight.verify(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3), "verkey", SIGN_ED25519_SHA512_SINGLE).isSuccess shouldBe true
      val controllerWithoutRight = new WalletAccessController(Set(), new TestWalletAccess)
      controllerWithoutRight.verify(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3), "verkey", SIGN_ED25519_SHA512_SINGLE).isSuccess shouldBe false
    }
  }

  class TestWalletAccess extends WalletAccess {
    import WalletAccess._

    override def newDid(keyType: KeyType): Try[(DID, VerKey)] = Try(("Did", "Verkey"))

    override def verKey(forDID: DID): Try[VerKey] = Try("Verkey")

    def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE): Try[SignatureResult] =
      Try(SignatureResult(Array[Byte](1, 2, 3), "VerKey"))

    def verify(signer: ParticipantId,
               msg: Array[Byte],
               sig: Array[Byte],
               verKeyUsed: Option[VerKey] = None,
               signType: SignType = SIGN_ED25519_SHA512_SINGLE
              ): Try[Boolean] = Try(true)

    def verify(msg: Array[Byte],
               sig: Array[Byte],
               verKeyUsed: VerKey,
               signType: SignType
              ): Try[Boolean] = Try(true)


    override def storeTheirDid(did: DID, verKey: VerKey): Try[Unit] = {
      Try(Unit)
    }

    override def createSchema(issuerDID:  DID,
                              name:  String,
                              version:  String,
                              data:  String): Try[(String, String)] = ???

    override def createCredDef(issuerDID:  DID,
                               schemaJson:  String,
                               tag:  String,
                               sigType:  Option[String],
                               revocationDetails:  Option[String]): Try[(String, String)] = ???

    override def createCredOffer(credDefId: String): Try[String] = ???

    override def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String): Try[String] = ???

    override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                            revRegistryId: String, blobStorageReaderHandle: ParticipantIndex): Try[String] = ???

    override def credentialsForProofReq(proofRequest: String): Try[String] = ???

    override def createProof(proofRequest: String, usedCredentials: String, schemas: String, credentialDefs: String, revStates: String): Try[String] = ???

    override def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String, revocRegDefs: String, revocRegs: String): Try[Boolean] = ???

    override def signRequest(submitterDID: DID, request: String): Try[LedgerRequest] = ???
  }
}

object WalletAccessTest extends TestWalletHelper {
  walletDetail.walletAPI.createWallet(wap)
  val newKey: NewKeyCreated = walletDetail.walletAPI.createNewKey()
  val _selfParticipantId: ParticipantId = ParticipantUtil.participantId(newKey.did, None)
  def walletAccess(selfParticipantId: ParticipantId=_selfParticipantId) =
    new WalletAccessLibindy(appConfig, walletDetail.walletAPI, selfParticipantId)
}

