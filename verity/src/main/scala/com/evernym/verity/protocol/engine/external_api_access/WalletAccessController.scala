package com.evernym.verity.protocol.engine.external_api_access

import com.evernym.verity.actor.wallet.{CreatedCredReq, TheirKeyStored}
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.protocol.engine.{DID, ParticipantId, VerKey}

import scala.concurrent.Future
import scala.util.{Failure, Try}

class WalletAccessController(accessRights: Set[AccessRight], walletAccessImpl: WalletAccess)
  extends WalletAccess {

  import WalletAccess._

  def runIfAllowed[T](right: AccessRight, f: (Try[T] => Unit) => Unit, handler: Try[T] => Unit): Unit =
    if(accessRights(right))
      f(handler)
    else
      handler(Failure(new IllegalAccessException))

  override def newDid(keyType: KeyType = KEY_ED25519)(handler: Try[(DID, VerKey)] => Unit): Unit =
    runIfAllowed(AccessNewDid, {walletAccessImpl.newDid(keyType)}, handler)

  override def verKey(forDID: DID)(handler: Try[VerKey] => Unit): Unit =
    runIfAllowed(AccessVerKey, {walletAccessImpl.verKey(forDID)}, handler)

  override def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                   (handler: Try[SignatureResult] => Unit): Unit =
    runIfAllowed(AccessSign, {walletAccessImpl.sign(msg)}, handler)

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKey] = None,
                      signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                     (handler: Try[Boolean] => Unit): Unit =
    runIfAllowed(AccessVerify, {walletAccessImpl.verify(signer, msg, sig, verKeyUsed, signType)}, handler)

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKey,
                      signType: SignType)
                     (handler: Try[Boolean] => Unit): Unit =
    runIfAllowed(AccessVerify, {walletAccessImpl.verify(msg, sig, verKeyUsed, signType)}, handler)

  override def storeTheirDid(did: DID, verKey: VerKey)(handler: Try[TheirKeyStored] => Unit): Unit =
    runIfAllowed(AccessStoreTheirDiD, {walletAccessImpl.storeTheirDid(did, verKey)}, handler)

  override def createSchema(issuerDID:  DID,
                            name:  String,
                            version:  String,
                            data:  String)
                           (handler: Try[(String, String)] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletAccessImpl.createSchema(issuerDID, name, version, data)}, handler)

  override def createCredDef(issuerDID:  DID,
                             schemaJson:  String,
                             tag:  String,
                             sigType:  Option[String],
                             revocationDetails:  Option[String])
                            (handler: Try[(String, String)] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletAccessImpl.createCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)},
      handler
    )

  override def createCredOffer(credDefId: String)(handler: Try[String] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletAccessImpl.createCredOffer(credDefId)}, handler)

  override def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String)
                            (handler: Try[CreatedCredReq] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletAccessImpl.createCredReq(credDefId, proverDID, credDefJson, credOfferJson)}, handler)

  override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                          revRegistryId: String, blobStorageReaderHandle: Int)
                         (handler: Try[String] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletAccessImpl.createCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)},
      handler
    )

  override def storeCred(credId: String, credDefJson: String,
                         credReqMetadataJson: String, credJson: String,
                         revRegDefJson: String)
                        (handler: Try[String] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletAccessImpl.storeCred(credId, credDefJson, credReqMetadataJson, credJson, revRegDefJson)},
      handler
    )

  override def credentialsForProofReq(proofRequest: String)(handler: Try[String] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletAccessImpl.credentialsForProofReq(proofRequest)}, handler)

  override def createProof(proofRequest: String,
                           usedCredentials: String,
                           schemas: String,
                           credentialDefs: String, revStates: String)
                          (handler: Try[String] => Unit): Unit = {
    runIfAllowed(
      AnonCreds,
      {walletAccessImpl.createProof(proofRequest, usedCredentials, schemas, credentialDefs, revStates)},
      handler
    )
  }

  override def verifyProof(proofRequest: String,
                           proof: String,
                           schemas: String,
                           credentialDefs: String,
                           revocRegDefs: String,
                           revocRegs: String)
                          (handler: Try[Boolean] => Unit): Unit = {
    runIfAllowed(
      AnonCreds,
      {walletAccessImpl.verifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)},
      handler
    )
  }

  override def signRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletAccessImpl.signRequest(submitterDID, request)}, handler)

  override def multiSignRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletAccessImpl.multiSignRequest(submitterDID, request)}, handler)

}
