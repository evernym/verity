package com.evernym.verity.protocol.testkit

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

  def alwaysVerifyAs(as: Boolean) =
    MockableWalletAccess(mockVerify = ()=>Try(as))

  def alwaysSignAs(as: Try[SignatureResult]=randomSig()) =
    MockableWalletAccess(mockVerify=trueVerify _, mockSign = {()=>as})

  def randomDid() = {
    val randomDid = new Array[Byte](16)
    Random.nextBytes(randomDid)
    val randomKey = new Array[Byte](32)
    Random.nextBytes(randomKey)
    Try((Base58Util.encode(randomDid), Base58Util.encode(randomKey)))
  }

  def randomVerKey(forDID: String) = {
    val randomKey = new Array[Byte](32)
    Random.nextBytes(randomKey)
    Try(Base58Util.encode(randomKey))
  }

  def randomSig() = {
    val randomSig = new Array[Byte](64)
    Random.nextBytes(randomSig)
    Try(external_api_access.SignatureResult(randomSig, "V1"))
  }
  def trueVerify() = Try(true)

}


class MockableWalletAccess(mockNewDid: () => Try[(String, String)] = randomDid  _,
                           mockVerKey: DID => Try[VerKey] =  randomVerKey,
                           mockSign:   () => Try[SignatureResult]  = randomSig  _,
                           mockVerify: () => Try[Boolean]          = trueVerify _,
                           anonCreds: AnonCredRequests = MockableAnonCredRequests.basic
                          ) extends WalletAccess {

  override def newDid(keyType: KeyType): Try[(DID, VerKey)] = mockNewDid()

  override def verKey(forDID: DID): Try[VerKey] = mockVerKey(forDID)

  override def sign(msg: Array[Byte], signType: SignType): Try[SignatureResult] = mockSign()

  override def signRequest(submitterDID: DID, request:  String): Try[LedgerRequest] = Try(LedgerRequest(request))

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKey] = None,
                      signType: SignType
                     ): Try[Boolean] = mockVerify()

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKey,
                      signType: SignType
                     ): Try[Boolean] = mockVerify()

  // Other party's DID is not needed for routing in tests. Secondly, tests don't have a wallet.
  override def storeTheirDid(did: DID, verKey: VerKey): Try[Unit] = Try(Unit)

  override def createSchema(issuerDID:  DID,
                            name:  String,
                            version:  String,
                            data:  String): Try[(String, String)] =
    anonCreds.createSchema(issuerDID, name, version, data)

  override def createCredDef(issuerDID:  DID,
                             schemaJson:  String,
                             tag:  String,
                             sigType:  Option[String],
                             revocationDetails:  Option[String]): Try[(String, String)] =
    anonCreds.createCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)

  override def createCredOffer(credDefId: String): Try[String] = anonCreds.createCredOffer(credDefId)

  override def createCredReq(credDefId: String,
                             proverDID: DID,
                             credDefJson: String,
                             credOfferJson: String): Try[String] =
    anonCreds.createCredReq(credDefId, proverDID, credDefJson, credOfferJson)

  override def createCred(credOfferJson: String,
                          credReqJson: String,
                          credValuesJson: String,
                          revRegistryId: String,
                          blobStorageReaderHandle: ParticipantIndex): Try[String] =
    anonCreds.createCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)

  override def credentialsForProofReq(proofRequest: String): Try[String] = anonCreds.credentialsForProofReq(proofRequest)

  override def createProof(proofRequest: String,
                           usedCredentials: String,
                           schemas: String,
                           credentialDefs: String,
                           revStates: String): Try[String] =
    anonCreds.createProof(proofRequest, usedCredentials, schemas, credentialDefs, revStates)

  override def verifyProof(proofRequest: String,
                           proof: String,
                           schemas: String,
                           credentialDefs: String,
                           revocRegDefs: String,
                           revocRegs: String): Try[Boolean] =
    anonCreds.verifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)
}
