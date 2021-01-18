package com.evernym.verity.protocol.engine.external_api_access

import com.evernym.verity.actor.wallet.CreatedCredReq
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.protocol.engine.{DID, ParticipantId, VerKey}

import scala.util.{Failure, Try}

class WalletAccessController(accessRights: Set[AccessRight], walletAccessImpl: WalletAccess)
  extends WalletAccess {

  import WalletAccess._

  def runIfAllowed[T](right: AccessRight, f: => Try[T]): Try[T] = {
    if (accessRights(right))
      f
    else
      Failure(new IllegalAccessException)
  }

  override def newDid(keyType: KeyType = KEY_ED25519): Try[(DID, VerKey)] =
    runIfAllowed(AccessNewDid, {walletAccessImpl.newDid(keyType)})

  override def verKey(forDID: DID): Try[VerKey] =
    runIfAllowed(AccessVerKey, {walletAccessImpl.verKey(forDID)})

  override def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE): Try[SignatureResult] =
    runIfAllowed(AccessSign, {walletAccessImpl.sign(msg)})

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKey] = None,
                      signType: SignType = SIGN_ED25519_SHA512_SINGLE): Try[Boolean] =
    runIfAllowed(AccessVerify, {walletAccessImpl.verify(signer, msg, sig, verKeyUsed, signType)})

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKey,
                      signType: SignType): Try[Boolean] =
    runIfAllowed(AccessVerify, {walletAccessImpl.verify(msg, sig, verKeyUsed, signType)})

  override def storeTheirDid(did: DID, verKey: VerKey): Try[Unit] =
    runIfAllowed(AccessStoreTheirDiD, {walletAccessImpl.storeTheirDid(did, verKey)})

  override def createSchema(issuerDID:  DID,
                            name:  String,
                            version:  String,
                            data:  String): Try[(String, String)] =
    runIfAllowed(AnonCreds, {walletAccessImpl.createSchema(issuerDID, name, version, data)})

  override def createCredDef(issuerDID:  DID,
                             schemaJson:  String,
                             tag:  String,
                             sigType:  Option[String],
                             revocationDetails:  Option[String]): Try[(String, String)] =
    runIfAllowed(AnonCreds, {walletAccessImpl.createCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)})

  override def createCredOffer(credDefId: String): Try[String] =
    runIfAllowed(AnonCreds, {walletAccessImpl.createCredOffer(credDefId)})

  override def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String): Try[CreatedCredReq] =
    runIfAllowed(AnonCreds, {walletAccessImpl.createCredReq(credDefId, proverDID, credDefJson, credOfferJson)})

  override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                 revRegistryId: String, blobStorageReaderHandle: Int): Try[String] =
    runIfAllowed(AnonCreds, {walletAccessImpl.createCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)})

  override def storeCred(credId: String, credDefJson: String,
                         credReqMetadataJson: String, credJson: String,
                         revRegDefJson: String): Try[String] =
    runIfAllowed(AnonCreds, {walletAccessImpl.storeCred(credId, credDefJson, credReqMetadataJson, credJson, revRegDefJson)})

  override def credentialsForProofReq(proofRequest: String): Try[String] =
    runIfAllowed(AnonCreds, {walletAccessImpl.credentialsForProofReq(proofRequest)})

  override def createProof(proofRequest: String,
                           usedCredentials: String,
                           schemas: String,
                           credentialDefs: String, revStates: String): Try[String] = {
    runIfAllowed(AnonCreds, {walletAccessImpl.createProof(
      proofRequest, usedCredentials, schemas, credentialDefs, revStates)})
  }

  override def verifyProof(proofRequest: String,
                           proof: String,
                           schemas: String,
                           credentialDefs: String,
                           revocRegDefs: String,
                           revocRegs: String): Try[Boolean] = {
    runIfAllowed(AnonCreds, {walletAccessImpl.verifyProof(
      proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)})
  }

  override def signRequest(submitterDID: DID, request: String): Try[LedgerRequest] =
    runIfAllowed(AnonCreds, {walletAccessImpl.signRequest(submitterDID, request)})

  override def multiSignRequest(submitterDID: DID, request: String): Try[LedgerRequest] =
    runIfAllowed(AnonCreds, {walletAccessImpl.multiSignRequest(submitterDID, request)})

}
