package com.evernym.verity.protocol.testkit

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.wallet.{AgentWalletSetupCompleted, CredCreated, CredDefCreated, CredForProofReqCreated, CredOfferCreated, CredReqCreated, CredStored, GetVerKeyOptResp, GetVerKeyResp, NewKeyCreated, ProofCreated, ProofVerifResult, SignedMsg, TheirKeyStored, VerifySigResult}
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.wallet.{SchemaCreated, WalletAccessAPI}
import com.evernym.verity.protocol.engine.WalletAccessTest.testWalletAPI
import com.evernym.verity.protocol.engine.{ParticipantId, ParticipantIndex}
import com.evernym.verity.protocol.engine.asyncapi.wallet.{AnonCredRequests, CredCreatedResult, CredDefCreatedResult, CredForProofResult, CredOfferCreatedResult, CredReqCreatedResult, CredStoredResult, DeprecatedWalletSetupResult, LedgerRequestResult, NewKeyResult, ProofCreatedResult, ProofVerificationResult, SchemaCreatedResult, SignedMsgResult, TheirKeyStoredResult, VerKeyOptResult, VerKeyResult, VerifiedSigResult, WalletAccess}
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.{KeyType, SignType}
import com.evernym.verity.protocol.testkit.MockableWalletAccess._
import com.evernym.verity.testkit.TestWallet
import com.evernym.verity.util.Base58Util
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.wallet_api.WalletAPI

import java.util.UUID
import scala.util.{Random, Try}

object MockableWalletAccess {
  def apply(): MockableWalletAccess = new MockableWalletAccess

  def apply(mockVerify: () => Try[VerifiedSigResult], mockSign: () => Try[SignedMsgResult]=randomSig _): MockableWalletAccess = {
    new MockableWalletAccess(mockVerify=mockVerify, mockSign=mockSign)
  }

  def apply(anonCreds: AnonCredRequests): MockableWalletAccess = new MockableWalletAccess(anonCreds = anonCreds)

  def walletAccess(): MockableWalletAccess = new MockableWalletAccess
  def alwaysVerifyAs(as: Boolean): MockableWalletAccess =
    MockableWalletAccess(mockVerify = ()=>Try(VerifiedSigResult(as)))

  def alwaysSignAs(as: Try[SignedMsgResult]=randomSig()): MockableWalletAccess =
    MockableWalletAccess(mockVerify=trueVerify _, mockSign = {()=>as})

  def newKey(): NewKeyResult = {
    val randomDid = new Array[Byte](16)
    Random.nextBytes(randomDid)
    val randomKey = new Array[Byte](32)
    Random.nextBytes(randomKey)
    NewKeyResult(Base58Util.encode(randomDid), Base58Util.encode(randomKey))
  }

  def randomDid(): Try[NewKeyResult] = {
    Try(newKey())
  }

  def randomVerKey(forDID: String): Try[VerKeyResult] = {
    val randomKey = new Array[Byte](32)
    Random.nextBytes(randomKey)
    Try(VerKeyResult(Base58Util.encode(randomKey)))
  }

  def randomVerKeyOpt(forDID: String): Try[VerKeyOptResult] = {
    val randomKey = new Array[Byte](32)
    Random.nextBytes(randomKey)
    Try(VerKeyOptResult(Option(Base58Util.encode(randomKey))))
  }

  def randomSig(): Try[SignedMsgResult] = {
    val randomSig = new Array[Byte](64)
    Random.nextBytes(randomSig)
    Try(SignedMsgResult(randomSig, "V1"))
  }
  def trueVerify(): Try[VerifiedSigResult] = Try(VerifiedSigResult(true))

}


class MockableWalletAccess(mockNewDid: () => Try[NewKeyResult] = randomDid  _,
                           mockVerKey: DidStr => Try[VerKeyResult] =  randomVerKey,
                           mockVerKeyOpt: DidStr => Try[VerKeyOptResult] = randomVerKeyOpt,
                           mockSign:   () => Try[SignedMsgResult]  = randomSig  _,
                           mockVerify: () => Try[VerifiedSigResult]  = trueVerify _,
                           anonCreds: AnonCredRequests = MockableAnonCredRequests.basic
                          ) extends WalletAccess {

  override def DEPRECATED_setupNewWallet(walletId: String, ownerDidPair: DidPair)(handler: Try[DeprecatedWalletSetupResult] => Unit): Unit =
    handler(Try(DeprecatedWalletSetupResult(ownerDidPair, newKey())))

  override def newDid(keyType: KeyType)(handler: Try[NewKeyResult] => Unit): Unit = handler(mockNewDid())

  override def verKey(forDID: DidStr)(handler: Try[VerKeyResult] => Unit): Unit = handler(mockVerKey(forDID))

  override def verKeyOpt(forDID: DidStr)(handler: Try[VerKeyOptResult] => Unit): Unit = handler(mockVerKeyOpt(forDID))

  override def sign(msg: Array[Byte], signType: SignType)(handler: Try[SignedMsgResult] => Unit): Unit = handler(mockSign())

  override def signRequest(submitterDID: DidStr, request: String)(handler: Try[LedgerRequestResult] => Unit): Unit = handler(Try(LedgerRequestResult(request)))

  override def multiSignRequest(submitterDID: DidStr, request:  String)(handler: Try[LedgerRequestResult] => Unit): Unit = handler(Try(LedgerRequestResult(request)))

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKeyStr] = None,
                      signType: SignType)
                     (handler: Try[VerifiedSigResult] => Unit): Unit = handler(mockVerify())

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKeyStr,
                      signType: SignType)
                     (handler: Try[VerifiedSigResult] => Unit): Unit = handler(mockVerify())

  // Other party's DID is not needed for routing in tests. Secondly, tests don't have a wallet.
  override def storeTheirDid(did: DidStr, verKey: VerKeyStr, ignoreIfAlreadyExists: Boolean = false)(handler: Try[TheirKeyStoredResult] => Unit): Unit =
    handler(Try(TheirKeyStoredResult(did, verKey)))

  override def createSchema(issuerDID:  DidStr,
                            name:  String,
                            version:  String,
                            data:  String)
                           (handler: Try[SchemaCreatedResult] => Unit): Unit =
    anonCreds.createSchema(issuerDID, name, version, data)(handler)

  override def createCredDef(issuerDID:  DidStr,
                             schemaJson:  String,
                             tag:  String,
                             sigType:  Option[String],
                             revocationDetails:  Option[String])
                            (handler: Try[CredDefCreatedResult] => Unit): Unit =
    anonCreds.createCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)(handler)

  override def createCredOffer(credDefId: String)(handler: Try[CredOfferCreatedResult] => Unit): Unit =
    anonCreds.createCredOffer(credDefId)(handler)

  override def createCredReq(credDefId: String,
                             proverDID: DidStr,
                             credDefJson: String,
                             credOfferJson: String)
                            (handler: Try[CredReqCreatedResult] => Unit): Unit =
    anonCreds.createCredReq(credDefId, proverDID, credDefJson, credOfferJson)(handler)

  override def createCred(credOfferJson: String,
                          credReqJson: String,
                          credValuesJson: String,
                          revRegistryId: String,
                          blobStorageReaderHandle: ParticipantIndex)
                         (handler: Try[CredCreatedResult] => Unit): Unit =
    anonCreds.createCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)(handler)

  override def storeCred(credId: String,
                         credDefJson: String,
                         credReqMetadataJson: String,
                         credJson: String,
                         revRegDefJson: String)
                        (handler: Try[CredStoredResult] => Unit): Unit =
  anonCreds.storeCred(credId, credDefJson, credReqMetadataJson, credJson, revRegDefJson)(handler)

  override def credentialsForProofReq(proofRequest: String)
                                     (handler: Try[CredForProofResult] => Unit): Unit =
    anonCreds.credentialsForProofReq(proofRequest)(handler)

  override def createProof(proofRequest: String,
                           usedCredentials: String,
                           schemas: String,
                           credentialDefs: String,
                           revStates: String)
                          (handler: Try[ProofCreatedResult] => Unit): Unit =
    anonCreds.createProof(proofRequest, usedCredentials, schemas, credentialDefs, revStates)(handler)

  override def verifyProof(proofRequest: String,
                           proof: String,
                           schemas: String,
                           credentialDefs: String,
                           revocRegDefs: String,
                           revocRegs: String)
                          (handler: Try[ProofVerificationResult] => Unit): Unit =
    anonCreds.verifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)(handler)
}