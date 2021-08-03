package com.evernym.verity.protocol.testkit

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.wallet.{AgentWalletSetupCompleted, CredCreated, CredDefCreated, CredForProofReqCreated, CredOfferCreated, CredReqCreated, CredStored, GetVerKeyOptResp, GetVerKeyResp, NewKeyCreated, ProofCreated, ProofVerifResult, SignedMsg, TheirKeyStored, VerifySigResult}
import com.evernym.verity.did.{DID, DidPair, VerKey}
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.wallet.{SchemaCreated, WalletAccessAPI}
import com.evernym.verity.protocol.engine.WalletAccessTest.testWalletAPI
import com.evernym.verity.protocol.engine.{ParticipantId, ParticipantIndex}
import com.evernym.verity.protocol.engine.asyncapi.wallet.{AnonCredRequests, WalletAccess}
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

  def apply(mockVerify: () => Try[VerifySigResult], mockSign: () => Try[SignedMsg]=randomSig _): MockableWalletAccess = {
    new MockableWalletAccess(mockVerify=mockVerify, mockSign=mockSign)
  }

  def apply(anonCreds: AnonCredRequests): MockableWalletAccess = new MockableWalletAccess(anonCreds = anonCreds)

  def walletAccess(): MockableWalletAccess = new MockableWalletAccess
  def alwaysVerifyAs(as: Boolean): MockableWalletAccess =
    MockableWalletAccess(mockVerify = ()=>Try(VerifySigResult(as)))

  def alwaysSignAs(as: Try[SignedMsg]=randomSig()): MockableWalletAccess =
    MockableWalletAccess(mockVerify=trueVerify _, mockSign = {()=>as})

  def newKey(): NewKeyCreated = {
    val randomDid = new Array[Byte](16)
    Random.nextBytes(randomDid)
    val randomKey = new Array[Byte](32)
    Random.nextBytes(randomKey)
    NewKeyCreated(Base58Util.encode(randomDid), Base58Util.encode(randomKey))
  }

  def randomDid(): Try[NewKeyCreated] = {
    Try(newKey())
  }

  def randomVerKey(forDID: String): Try[GetVerKeyResp] = {
    val randomKey = new Array[Byte](32)
    Random.nextBytes(randomKey)
    Try(GetVerKeyResp(Base58Util.encode(randomKey)))
  }

  def randomVerKeyOpt(forDID: String): Try[GetVerKeyOptResp] = {
    val randomKey = new Array[Byte](32)
    Random.nextBytes(randomKey)
    Try(GetVerKeyOptResp(Option(Base58Util.encode(randomKey))))
  }

  def randomSig(): Try[SignedMsg] = {
    val randomSig = new Array[Byte](64)
    Random.nextBytes(randomSig)
    Try(SignedMsg(randomSig, "V1"))
  }
  def trueVerify(): Try[VerifySigResult] = Try(VerifySigResult(true))

}


class MockableWalletAccess(mockNewDid: () => Try[NewKeyCreated] = randomDid  _,
                           mockVerKey: DID => Try[GetVerKeyResp] =  randomVerKey,
                           mockVerKeyOpt: DID => Try[GetVerKeyOptResp] = randomVerKeyOpt,
                           mockSign:   () => Try[SignedMsg]  = randomSig  _,
                           mockVerify: () => Try[VerifySigResult]  = trueVerify _,
                           anonCreds: AnonCredRequests = MockableAnonCredRequests.basic
                          ) extends WalletAccess {

  override def DEPRECATED_setupNewWallet(walletId: String, ownerDidPair: DidPair)(handler: Try[AgentWalletSetupCompleted] => Unit): Unit =
    handler(Try(AgentWalletSetupCompleted(ownerDidPair, newKey())))

  override def newDid(keyType: KeyType)(handler: Try[NewKeyCreated] => Unit): Unit = handler(mockNewDid())

  override def verKey(forDID: DID)(handler: Try[GetVerKeyResp] => Unit): Unit = handler(mockVerKey(forDID))

  override def verKeyOpt(forDID: DID)(handler: Try[GetVerKeyOptResp] => Unit): Unit = handler(mockVerKeyOpt(forDID))

  override def sign(msg: Array[Byte], signType: SignType)(handler: Try[SignedMsg] => Unit): Unit = handler(mockSign())

  override def signRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit = handler(Try(LedgerRequest(request)))

  override def multiSignRequest(submitterDID: DID, request:  String)(handler: Try[LedgerRequest] => Unit): Unit = handler(Try(LedgerRequest(request)))

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKey] = None,
                      signType: SignType)
                     (handler: Try[VerifySigResult] => Unit): Unit = handler(mockVerify())

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKey,
                      signType: SignType)
                     (handler: Try[VerifySigResult] => Unit): Unit = handler(mockVerify())

  // Other party's DID is not needed for routing in tests. Secondly, tests don't have a wallet.
  override def storeTheirDid(did: DID, verKey: VerKey, ignoreIfAlreadyExists: Boolean = false)(handler: Try[TheirKeyStored] => Unit): Unit =
    handler(Try(TheirKeyStored(did, verKey)))

  override def createSchema(issuerDID:  DID,
                            name:  String,
                            version:  String,
                            data:  String)
                           (handler: Try[SchemaCreated] => Unit): Unit =
    anonCreds.createSchema(issuerDID, name, version, data)(handler)

  override def createCredDef(issuerDID:  DID,
                             schemaJson:  String,
                             tag:  String,
                             sigType:  Option[String],
                             revocationDetails:  Option[String])
                            (handler: Try[CredDefCreated] => Unit): Unit =
    anonCreds.createCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)(handler)

  override def createCredOffer(credDefId: String)(handler: Try[CredOfferCreated] => Unit): Unit =
    anonCreds.createCredOffer(credDefId)(handler)

  override def createCredReq(credDefId: String,
                             proverDID: DID,
                             credDefJson: String,
                             credOfferJson: String)
                            (handler: Try[CredReqCreated] => Unit): Unit =
    anonCreds.createCredReq(credDefId, proverDID, credDefJson, credOfferJson)(handler)

  override def createCred(credOfferJson: String,
                          credReqJson: String,
                          credValuesJson: String,
                          revRegistryId: String,
                          blobStorageReaderHandle: ParticipantIndex)
                         (handler: Try[CredCreated] => Unit): Unit =
    anonCreds.createCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)(handler)

  override def storeCred(credId: String,
                         credDefJson: String,
                         credReqMetadataJson: String,
                         credJson: String,
                         revRegDefJson: String)
                        (handler: Try[CredStored] => Unit): Unit =
  anonCreds.storeCred(credId, credDefJson, credReqMetadataJson, credJson, revRegDefJson)(handler)

  override def credentialsForProofReq(proofRequest: String)
                                     (handler: Try[CredForProofReqCreated] => Unit): Unit =
    anonCreds.credentialsForProofReq(proofRequest)(handler)

  override def createProof(proofRequest: String,
                           usedCredentials: String,
                           schemas: String,
                           credentialDefs: String,
                           revStates: String)
                          (handler: Try[ProofCreated] => Unit): Unit =
    anonCreds.createProof(proofRequest, usedCredentials, schemas, credentialDefs, revStates)(handler)

  override def verifyProof(proofRequest: String,
                           proof: String,
                           schemas: String,
                           credentialDefs: String,
                           revocRegDefs: String,
                           revocRegs: String)
                          (handler: Try[ProofVerifResult] => Unit): Unit =
    anonCreds.verifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)(handler)
}