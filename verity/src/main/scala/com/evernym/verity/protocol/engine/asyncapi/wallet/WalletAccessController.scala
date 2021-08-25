package com.evernym.verity.protocol.engine.asyncapi.wallet

import com.evernym.verity.did.{DidStr, DidPair, VerKeyStr}
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.protocol.engine.asyncapi.{AccessNewDid, AccessRight, AccessSign, AccessStoreTheirDiD,
  AccessVerKey, AccessVerify, AnonCreds, AsyncOpRunner, BaseAccessController, DEPRECATED_AccessSetupNewWallet}
import com.evernym.verity.protocol.engine.ParticipantId

import scala.util.Try

class WalletAccessController(val accessRights: Set[AccessRight],
                             val walletExecutor: WalletAsyncOps)
                            (implicit val asyncOpRunner: AsyncOpRunner)

  extends WalletAccess
    with BaseAccessController {

  import WalletAccess._

  def handleResult[T](handler: Try[T] => Unit): Try[T] => Unit = {
    {t: Try[_] => walletExecutor.handleResult(t, handler)}
  }

  def DEPRECATED_setupNewWallet(walletId: String,
                                ownerDidPair: DidPair)
                               (handler: Try[DeprecatedWalletSetupResult] => Unit): Unit =
    runIfAllowed(
      DEPRECATED_AccessSetupNewWallet,
      {walletExecutor.DEPRECATED_setupNewWallet(walletId, ownerDidPair)},
      handleResult(handler)
    )

  override def newDid(keyType: KeyType = KEY_ED25519)(handler: Try[NewKeyResult] => Unit): Unit =
    runIfAllowed(
      AccessNewDid,
      {walletExecutor.runNewDid(keyType)},
      handleResult(handler)
    )

  override def verKey(forDID: DidStr)(handler: Try[VerKeyResult] => Unit): Unit = {
    runIfAllowed(
      AccessVerKey,
      {walletExecutor.runVerKey(forDID)},
      handleResult(handler)
    )
  }

  override def verKeyOpt(forDID: DidStr)(handler: Try[VerKeyOptResult] => Unit): Unit =
    runIfAllowed(
      AccessVerKey,
      {walletExecutor.runVerKeyOpt(forDID)},
      handleResult(handler)
    )

  override def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                   (handler: Try[SignedMsgResult] => Unit): Unit =
    runIfAllowed(
      AccessSign,
      {walletExecutor.runSign(msg)},
      handleResult(handler)
    )

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKeyStr] = None,
                      signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                     (handler: Try[VerifiedSigResult] => Unit): Unit =
    runIfAllowed(
      AccessVerify,
      {walletExecutor.runVerify(signer, msg, sig, verKeyUsed, signType)},
      handleResult(handler)
    )

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKeyStr,
                      signType: SignType)
                     (handler: Try[VerifiedSigResult] => Unit): Unit =
    runIfAllowed(
      AccessVerify,
      {walletExecutor.runVerify(msg, sig, verKeyUsed, signType)},
      handleResult(handler)
    )

  override def storeTheirDid(did: DidStr,
                             verKey: VerKeyStr,
                             ignoreIfAlreadyExists: Boolean = false)
                            (handler: Try[TheirKeyStoredResult] => Unit): Unit =
    runIfAllowed(
      AccessStoreTheirDiD,
      {walletExecutor.runStoreTheirDid(did, verKey, ignoreIfAlreadyExists)},
      handleResult(handler)
    )

  override def createSchema(issuerDID:  DidStr,
                            name:  String,
                            version:  String,
                            data:  String)
                           (handler: Try[SchemaCreatedResult] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runCreateSchema(issuerDID, name, version, data)},
      handleResult(handler)
    )

  override def createCredDef(issuerDID: DidStr,
                             schemaJson: String,
                             tag: String,
                             sigType:  Option[String],
                             revocationDetails: Option[String])
                            (handler: Try[CredDefCreatedResult] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runCreateCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)},
      handleResult(handler)
    )

  override def createCredOffer(credDefId: String)(handler: Try[CredOfferCreatedResult] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runCreateCredOffer(credDefId)},
      handleResult(handler)
    )

  override def createCredReq(credDefId: String,
                             proverDID: DidStr,
                             credDefJson: String,
                             credOfferJson: String)
                            (handler: Try[CredReqCreatedResult] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runCreateCredReq(credDefId, proverDID, credDefJson, credOfferJson)},
      handleResult(handler)
    )

  override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                          revRegistryId: String, blobStorageReaderHandle: Int)
                         (handler: Try[CredCreatedResult] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runCreateCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)},
      handleResult(handler)
    )

  override def storeCred(credId: String,
                         credDefJson: String,
                         credReqMetadataJson: String,
                         credJson: String,
                         revRegDefJson: String)
                        (handler: Try[CredStoredResult] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runStoreCred(credId, credDefJson, credReqMetadataJson, credJson, revRegDefJson)},
      handleResult(handler)
    )

  override def credentialsForProofReq(proofRequest: String)(handler: Try[CredForProofResult] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runCredentialsForProofReq(proofRequest)},
      handleResult(handler)
    )

  override def createProof(proofRequest: String,
                           usedCredentials: String,
                           schemas: String,
                           credentialDefs: String, revStates: String)
                          (handler: Try[ProofCreatedResult] => Unit): Unit = {
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runCreateProof(proofRequest, usedCredentials, schemas, credentialDefs, revStates)},
      handleResult(handler)
    )
  }

  override def verifyProof(proofRequest: String,
                           proof: String,
                           schemas: String,
                           credentialDefs: String,
                           revocRegDefs: String,
                           revocRegs: String)
                          (handler: Try[ProofVerificationResult] => Unit): Unit = {
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runVerifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)},
      handleResult(handler)
    )
  }

  override def signRequest(submitterDID: DidStr,
                           request: String)
                          (handler: Try[LedgerRequestResult] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runSignRequest(submitterDID, request)},
      handleResult(handler)
    )

  override def multiSignRequest(submitterDID: DidStr,
                                request: String)
                               (handler: Try[LedgerRequestResult] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runMultiSignRequest(submitterDID, request)},
      handleResult(handler)
    )

}
