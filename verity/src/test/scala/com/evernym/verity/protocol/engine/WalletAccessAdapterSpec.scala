package com.evernym.verity.protocol.engine

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.wallet._
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.wallet.WalletAccessAPI
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccessAdapter
import com.evernym.verity.protocol.engine.asyncapi.{AccessNewDid, AccessSign, AccessVerify}
import com.evernym.verity.testkit.{BasicSpec, HasDefaultTestWallet, TestWallet}
import com.evernym.verity.util.{ParticipantUtil, TestExecutionContextProvider}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.wallet_api.WalletAPI

import scala.concurrent.ExecutionContext
import scala.util.Try


class WalletAccessAdapterSpec
  extends BasicSpec
    with MockAsyncOpRunner {

  implicit def asyncAPIContext: AsyncAPIContext = AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  val testWallet = new TestWallet(ecp.futureExecutionContext, false)
  implicit val wap: WalletAPIParam = testWallet.wap

  "Wallet access controller" - {
    "mixed functions should pass if having correct access rights" in {
      val controller = new WalletAccessAdapter(testWalletAPI(testWallet.testWalletAPI, testWallet.walletId))
      controller.newDid(){ result => result.isSuccess shouldBe true }
      controller.sign(Array[Byte](1, 2, 3)){ result => result.isSuccess shouldBe false }
      controller.verify("participantId", Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)) { result =>
        result.isSuccess shouldBe true
      }
    }
    "newDid should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessAdapter(testWalletAPI(testWallet.testWalletAPI, testWallet.walletId))
      controllerWithRight.newDid() { result => result.isSuccess shouldBe true }
      val controllerWithoutRight = new WalletAccessAdapter(testWalletAPI(testWallet.testWalletAPI, testWallet.walletId))
      controllerWithoutRight.newDid(){ result => result.isSuccess shouldBe false }
    }
    "sign should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessAdapter(testWalletAPI(testWallet.testWalletAPI, testWallet.walletId))
      controllerWithRight.sign(Array[Byte](1, 2, 3)){ result => result.isSuccess shouldBe true }
      val controllerWithoutRight = new WalletAccessAdapter(testWalletAPI(testWallet.testWalletAPI, testWallet.walletId))
      controllerWithoutRight.sign(Array[Byte](1, 2, 3)){ result => result.isSuccess shouldBe false }
    }
    "participantId verify should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessAdapter(testWalletAPI(testWallet.testWalletAPI, testWallet.walletId))
      controllerWithRight.verify("participantId", Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)) { result =>
        result.isSuccess shouldBe true
      }
      val controllerWithoutRight = new WalletAccessAdapter(testWalletAPI(testWallet.testWalletAPI, testWallet.walletId))
      controllerWithoutRight.verify("participantId", Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)) { result =>
        result.isSuccess shouldBe false
      }
    }

    "verkey verify should pass if having correct access rights" in {
      val controllerWithRight = new WalletAccessAdapter(testWalletAPI(testWallet.testWalletAPI, testWallet.walletId))
      controllerWithRight.verify(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3), "verkey", SIGN_ED25519_SHA512_SINGLE) { result =>
        result.isSuccess shouldBe true
      }
      val controllerWithoutRight = new WalletAccessAdapter(testWalletAPI(testWallet.testWalletAPI, testWallet.walletId))
      controllerWithoutRight.verify(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3), "verkey", SIGN_ED25519_SHA512_SINGLE) { result =>
        result.isSuccess shouldBe false
      }
    }
  }

  def testWalletAPI(walletApi: WalletAPI,
                    selfParticipantId: ParticipantId)
                   (implicit wap: WalletAPIParam, asyncAPIContext: AsyncAPIContext): WalletAccessAPI =
    new WalletAccessAPI(walletApi, selfParticipantId) {

      import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess._

      override def DEPRECATED_setupNewWallet(walletId: String, ownerDidPair: DidPair): Unit =
        Try(AgentWalletSetupCompleted(ownerDidPair, NewKeyCreated("Did", "Verkey")))

      override def runNewDid(keyType: KeyType): Unit = Try(NewKeyCreated("Did", "Verkey"))

      override def runVerKey(forDID: DidStr): Unit = Try(GetVerKeyResp("Verkey"))

      override def runVerKeyOpt(forDID: DidStr): Unit = Try(GetVerKeyOptResp(Option("Verkey")))

      override def runSign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE): Unit =
        Try(SignedMsg(Array[Byte](1, 2, 3), "VerKey"))

      override def runVerify(signer: ParticipantId,
                             msg: Array[Byte],
                             sig: Array[Byte],
                             verKeyUsed: Option[VerKeyStr] = None,
                             signType: SignType = SIGN_ED25519_SHA512_SINGLE): Unit = Try(VerifySigResult(true))

      override def runVerify(msg: Array[Byte],
                             sig: Array[Byte],
                             verKeyUsed: VerKeyStr,
                             signType: SignType): Unit = Try(VerifySigResult(true))


      override def runStoreTheirDid(did: DidStr, verKey: VerKeyStr, ignoreIfAlreadyExists: Boolean = false): Unit =
        Try(TheirKeyStored(did, verKey))

      override def runCreateSchema(issuerDID:  DidStr,
                                   name:  String,
                                   version:  String,
                                   data:  String): Unit = ???

      override def runCreateCredDef(issuerDID:  DidStr,
                                    schemaJson:  String,
                                    tag:  String,
                                    sigType:  Option[String],
                                    revocationDetails:  Option[String]): Unit = ???

      override def runCreateCredOffer(credDefId: String): Unit = ???

      override def runCreateCredReq(credDefId: String,
                                    proverDID: DidStr,
                                    credDefJson: String,
                                    credOfferJson: String): Unit = ???

      override def runCreateCred(credOfferJson: String,
                                 credReqJson: String,
                                 credValuesJson: String,
                                 revRegistryId: String,
                                 blobStorageReaderHandle: ParticipantIndex): Unit = ???

      override def runStoreCred(credId: String,
                                credReqMetadataJson: String,
                                credJson: String,
                                credDefJson: String,
                                revRegDefJson: String): Unit = ???

      override def runCredentialsForProofReq(proofRequest: String): Unit = ???

      override def runCreateProof(proofRequest: String,
                                  usedCredentials: String,
                                  schemas: String,
                                  credentialDefs: String,
                                  revStates: String): Unit = ???

      override def runVerifyProof(proofRequest: String,
                                  proof: String,
                                  schemas: String,
                                  credentialDefs: String,
                                  revocRegDefs: String,
                                  revocRegs: String): Unit = ???

      override def runSignRequest(submitterDID: DidStr,
                                  request: String): Unit = ???

      override def runMultiSignRequest(submitterDID: DidStr, request: String): Unit = ???
  }
}

object WalletAccessTest
  extends HasDefaultTestWallet {

  implicit def asyncAPIContext: AsyncAPIContext =
    AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)

  testWalletAPI.executeSync[WalletCreated.type](CreateWallet())
  val newKey: NewKeyCreated = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey())
  val _selfParticipantId: ParticipantId = ParticipantUtil.participantId(newKey.did, None)
  def walletAccess(selfParticipantId: ParticipantId=_selfParticipantId) =
    new WalletAccessAPI(testWalletAPI, selfParticipantId)

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}

