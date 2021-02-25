package com.evernym.verity.protocol.testkit

import com.evernym.verity.actor.wallet.{CreatedCredReq, TheirKeyStored}
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess.{KeyType, SignType}
import com.evernym.verity.protocol.engine.{external_api_access, _}
import com.evernym.verity.protocol.engine.external_api_access.{AnonCredRequests, SignatureResult, WalletAccess}
import com.evernym.verity.protocol.testkit.MockableWalletAccess._
import com.evernym.verity.util.Base58Util

import scala.util.{Random, Try}

object MockableWalletAccess {
  def apply(): MockableWalletAccess = new MockableWalletAccess

  def apply(mockVerify: () => Try[Boolean], mockSign: () => Try[SignatureResult]=randomSig _): MockableWalletAccess = {
    new MockableWalletAccess(mockVerify=mockVerify, mockSign=mockSign)
  }

  def apply(anonCreds: AnonCredRequests): MockableWalletAccess = new MockableWalletAccess(anonCreds = anonCreds)

  def alwaysVerifyAs(as: Boolean): MockableWalletAccess =
    MockableWalletAccess(mockVerify = ()=>Try(as))

  def alwaysSignAs(as: Try[SignatureResult]=randomSig()): MockableWalletAccess =
    MockableWalletAccess(mockVerify=trueVerify _, mockSign = {()=>as})

  def randomDid(): Try[(String, String)] = {
    val randomDid = new Array[Byte](16)
    Random.nextBytes(randomDid)
    val randomKey = new Array[Byte](32)
    Random.nextBytes(randomKey)
    Try((Base58Util.encode(randomDid), Base58Util.encode(randomKey)))
  }

  def randomVerKey(forDID: String): Try[String] = {
    val randomKey = new Array[Byte](32)
    Random.nextBytes(randomKey)
    Try(Base58Util.encode(randomKey))
  }

  def randomSig(): Try[SignatureResult] = {
    val randomSig = new Array[Byte](64)
    Random.nextBytes(randomSig)
    Try(external_api_access.SignatureResult(randomSig, "V1"))
  }
  def trueVerify(): Try[Boolean] = Try(true)

}


class MockableWalletAccess(mockNewDid: () => Try[(String, String)] = randomDid  _,
                           mockVerKey: DID => Try[VerKey] =  randomVerKey,
                           mockSign:   () => Try[SignatureResult]  = randomSig  _,
                           mockVerify: () => Try[Boolean]          = trueVerify _,
                           anonCreds: AnonCredRequests = MockableAnonCredRequests.basic
                          ) extends WalletAccess {

  override def newDid(keyType: KeyType)(handler: Try[(DID, VerKey)] => Unit): Unit = handler(mockNewDid())

  override def verKey(forDID: DID)(handler: Try[VerKey] => Unit): Unit = handler(mockVerKey(forDID))

  override def sign(msg: Array[Byte], signType: SignType)(handler: Try[SignatureResult] => Unit): Unit = handler(mockSign())

  override def signRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit = handler(Try(LedgerRequest(request)))

  override def multiSignRequest(submitterDID: DID, request:  String)(handler: Try[LedgerRequest] => Unit): Unit = handler(Try(LedgerRequest(request)))

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKey] = None,
                      signType: SignType)
                     (handler: Try[Boolean] => Unit): Unit = handler(mockVerify())

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKey,
                      signType: SignType)
                     (handler: Try[Boolean] => Unit): Unit = handler(mockVerify())

  // Other party's DID is not needed for routing in tests. Secondly, tests don't have a wallet.
  override def storeTheirDid(did: DID, verKey: VerKey)(handler: Try[TheirKeyStored] => Unit): Unit =
    handler(Try(TheirKeyStored(did, verKey)))

  override def createSchema(issuerDID:  DID,
                            name:  String,
                            version:  String,
                            data:  String)
                           (handler: Try[(String, String)] => Unit): Unit =
    anonCreds.createSchema(issuerDID, name, version, data)(handler)

  override def createCredDef(issuerDID:  DID,
                             schemaJson:  String,
                             tag:  String,
                             sigType:  Option[String],
                             revocationDetails:  Option[String])
                            (handler: Try[(String, String)] => Unit): Unit =
    anonCreds.createCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)(handler)

  override def createCredOffer(credDefId: String)(handler: Try[String] => Unit): Unit =
    anonCreds.createCredOffer(credDefId)(handler)

  override def createCredReq(credDefId: String,
                             proverDID: DID,
                             credDefJson: String,
                             credOfferJson: String)
                            (handler: Try[CreatedCredReq] => Unit): Unit =
    anonCreds.createCredReq(credDefId, proverDID, credDefJson, credOfferJson)(handler)

  override def createCred(credOfferJson: String,
                          credReqJson: String,
                          credValuesJson: String,
                          revRegistryId: String,
                          blobStorageReaderHandle: ParticipantIndex)
                         (handler: Try[String] => Unit): Unit =
    anonCreds.createCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)(handler)

  override def storeCred(credId: String,
                         credDefJson: String,
                         credReqMetadataJson: String,
                         credJson: String,
                         revRegDefJson: String)
                        (handler: Try[String] => Unit): Unit =
  anonCreds.storeCred(credId, credDefJson, credReqMetadataJson, credJson, revRegDefJson)(handler)

  override def credentialsForProofReq(proofRequest: String)
                                     (handler: Try[String] => Unit): Unit =
    anonCreds.credentialsForProofReq(proofRequest)(handler)

  override def createProof(proofRequest: String,
                           usedCredentials: String,
                           schemas: String,
                           credentialDefs: String,
                           revStates: String)
                          (handler: Try[String] => Unit): Unit =
    anonCreds.createProof(proofRequest, usedCredentials, schemas, credentialDefs, revStates)(handler)

  override def verifyProof(proofRequest: String,
                           proof: String,
                           schemas: String,
                           credentialDefs: String,
                           revocRegDefs: String,
                           revocRegs: String)
                          (handler: Try[Boolean] => Unit): Unit =
    anonCreds.verifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)(handler)
}
