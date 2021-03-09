package com.evernym.verity.protocol.engine

import akka.actor.ActorRef
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.wallet._
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.wallet.{SchemaCreated, WalletAccessAPI}
import com.evernym.verity.protocol.engine.asyncapi.wallet.{WalletAccess, WalletAccessController}
import com.evernym.verity.protocol.engine.asyncapi.{AccessNewDid, AccessSign, AccessVerify}
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.testkit.{BasicSpec, HasDefaultTestWallet}
import com.evernym.verity.util.ParticipantUtil

import scala.util.Try


class WalletAccessControllerSpec
  extends BasicSpec
    with MockAsyncOpRunner {

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
    import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess._

    override def DEPRECATED_setupNewWallet(walletId: String, withTheirDIDPair: DidPair)(handler: Try[NewKeyCreated] => Unit): Unit =
      handler(Try(NewKeyCreated("Did", "Verkey")))

    override def newDid(keyType: KeyType)(handler: Try[NewKeyCreated] => Unit): Unit = handler(Try(NewKeyCreated("Did", "Verkey")))

    override def verKey(forDID: DID)(handler: Try[GetVerKeyResp] => Unit): Unit = handler(Try(GetVerKeyResp("Verkey")))

    override def verKeyOpt(forDID: DID)(handler: Try[GetVerKeyOptResp] => Unit): Unit = handler(Try(GetVerKeyOptResp(Option("Verkey"))))

    override def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                     (handler: Try[SignedMsg] => Unit): Unit =
      handler(Try(SignedMsg(Array[Byte](1, 2, 3), "VerKey")))

    override def verify(signer: ParticipantId,
                        msg: Array[Byte],
                        sig: Array[Byte],
                        verKeyUsed: Option[VerKey] = None,
                        signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                       (handler: Try[VerifySigResult] => Unit): Unit = handler(Try(VerifySigResult(true)))

    override def verify(msg: Array[Byte],
                        sig: Array[Byte],
                        verKeyUsed: VerKey,
                        signType: SignType)
                       (handler: Try[VerifySigResult] => Unit): Unit = handler(Try(VerifySigResult(true)))


    override def storeTheirDid(did: DID, verKey: VerKey, ignoreIfAlreadyExists: Boolean = false)(handler: Try[TheirKeyStored] => Unit): Unit =
      handler(Try(TheirKeyStored(did, verKey)))

    override def createSchema(issuerDID:  DID,
                              name:  String,
                              version:  String,
                              data:  String)
                             (handler: Try[SchemaCreated] => Unit): Unit = ???

    override def createCredDef(issuerDID:  DID,
                               schemaJson:  String,
                               tag:  String,
                               sigType:  Option[String],
                               revocationDetails:  Option[String])
                              (handler: Try[CredDefCreated] => Unit): Unit = ???

    override def createCredOffer(credDefId: String)(handler: Try[CredOfferCreated] => Unit): Unit = ???

    override def createCredReq(credDefId: String,
                               proverDID: DID,
                               credDefJson: String,
                               credOfferJson: String)
                              (handler: Try[CredReqCreated] => Unit): Unit = ???

    override def createCred(credOfferJson: String,
                            credReqJson: String,
                            credValuesJson: String,
                            revRegistryId: String,
                            blobStorageReaderHandle: ParticipantIndex)
                           (handler: Try[CredCreated] => Unit): Unit = ???

    override def storeCred(credId: String,
                           credReqMetadataJson: String,
                           credJson: String,
                           credDefJson: String,
                           revRegDefJson: String)
                          (handler: Try[CredStored] => Unit): Unit = ???

    override def credentialsForProofReq(proofRequest: String)
                                       (handler: Try[CredForProofReqCreated] => Unit): Unit = ???

    override def createProof(proofRequest: String,
                             usedCredentials: String,
                             schemas: String,
                             credentialDefs: String,
                             revStates: String)
                            (handler: Try[ProofCreated] => Unit): Unit = ???

    override def verifyProof(proofRequest: String,
                             proof: String,
                             schemas: String,
                             credentialDefs: String,
                             revocRegDefs: String,
                             revocRegs: String)
                            (handler: Try[ProofVerifResult] => Unit): Unit = ???

    override def signRequest(submitterDID: DID,
                             request: String)
                            (handler: Try[LedgerRequest] => Unit): Unit = ???

    override def multiSignRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit = ???
  }
}

object WalletAccessTest
  extends HasDefaultTestWallet {

  implicit def asyncAPIContext: AsyncAPIContext =
    AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)

  testWalletAPI.executeSync[WalletCreated.type](CreateWallet)
  val newKey: NewKeyCreated = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey())
  val _selfParticipantId: ParticipantId = ParticipantUtil.participantId(newKey.did, None)
  def walletAccess(selfParticipantId: ParticipantId=_selfParticipantId) =
    new WalletAccessAPI(testWalletAPI, selfParticipantId)
}

