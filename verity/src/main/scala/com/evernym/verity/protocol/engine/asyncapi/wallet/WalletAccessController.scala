package com.evernym.verity.protocol.engine.asyncapi.wallet

import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.wallet.{AgentWalletSetupCompleted, CredCreated, CredDefCreated, CredForProofReqCreated, CredOfferCreated, CredReqCreated, CredStored, GetVerKeyOptResp, GetVerKeyResp, NewKeyCreated, ProofCreated, ProofVerifResult, SignedMsg, TheirKeyStored, VerifySigResult}
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.protocol.container.asyncapis.wallet.SchemaCreated
import com.evernym.verity.protocol.engine.asyncapi.{AccessNewDid, AccessRight, AccessSign, AccessStoreTheirDiD, AccessVerKey, AccessVerify, AnonCreds, AsyncOpRunner, BaseAccessController, DEPRECATED_AccessSetupNewWallet}
import com.evernym.verity.protocol.engine.{DID, ParticipantId, VerKey}

import scala.util.Try

class WalletAccessController(val accessRights: Set[AccessRight],
                             val walletExecutor: WalletAsyncOps)
                            (implicit val asyncOpRunner: AsyncOpRunner)

  extends WalletAccess
    with BaseAccessController {

  import WalletAccess._

  def DEPRECATED_setupNewWallet(walletId: String, ownerDidPair: DidPair)(handler: Try[AgentWalletSetupCompleted] => Unit): Unit =
    runIfAllowed(DEPRECATED_AccessSetupNewWallet, {walletExecutor.DEPRECATED_setupNewWallet(walletId, ownerDidPair)}, handler)

  override def newDid(keyType: KeyType = KEY_ED25519)(handler: Try[NewKeyCreated] => Unit): Unit =
    runIfAllowed(AccessNewDid, {walletExecutor.runNewDid(keyType)}, handler)

  override def verKey(forDID: DID)(handler: Try[GetVerKeyResp] => Unit): Unit =
    runIfAllowed(AccessVerKey, {walletExecutor.runVerKey(forDID)}, handler)

  override def verKeyOpt(forDID: DID)(handler: Try[GetVerKeyOptResp] => Unit): Unit =
    runIfAllowed(AccessVerKey, {walletExecutor.runVerKeyOpt(forDID)}, handler)

  override def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                   (handler: Try[SignedMsg] => Unit): Unit =
    runIfAllowed(AccessSign, {walletExecutor.runSign(msg)}, handler)

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKey] = None,
                      signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                     (handler: Try[VerifySigResult] => Unit): Unit =
    runIfAllowed(AccessVerify, {walletExecutor.runVerify(signer, msg, sig, verKeyUsed, signType)}, handler)

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKey,
                      signType: SignType)
                     (handler: Try[VerifySigResult] => Unit): Unit =
    runIfAllowed(AccessVerify, {walletExecutor.runVerify(msg, sig, verKeyUsed, signType)}, handler)

  override def storeTheirDid(did: DID, verKey: VerKey, ignoreIfAlreadyExists: Boolean = false)(handler: Try[TheirKeyStored] => Unit): Unit =
    runIfAllowed(AccessStoreTheirDiD, {walletExecutor.runStoreTheirDid(did, verKey, ignoreIfAlreadyExists)}, handler)

  override def createSchema(issuerDID:  DID,
                            name:  String,
                            version:  String,
                            data:  String)
                           (handler: Try[SchemaCreated] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletExecutor.runCreateSchema(issuerDID, name, version, data)}, handler)

  override def createCredDef(issuerDID: DID,
                             schemaJson: String,
                             tag: String,
                             sigType:  Option[String],
                             revocationDetails: Option[String])
                            (handler: Try[CredDefCreated] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runCreateCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)},
      handler
    )

  override def createCredOffer(credDefId: String)(handler: Try[CredOfferCreated] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletExecutor.runCreateCredOffer(credDefId)}, handler)

  override def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String)
                            (handler: Try[CredReqCreated] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletExecutor.runCreateCredReq(credDefId, proverDID, credDefJson, credOfferJson)}, handler)

  override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                          revRegistryId: String, blobStorageReaderHandle: Int)
                         (handler: Try[CredCreated] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runCreateCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)},
      handler
    )

  override def storeCred(credId: String, credDefJson: String,
                         credReqMetadataJson: String, credJson: String,
                         revRegDefJson: String)
                        (handler: Try[CredStored] => Unit): Unit =
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runStoreCred(credId, credDefJson, credReqMetadataJson, credJson, revRegDefJson)},
      handler
    )

  override def credentialsForProofReq(proofRequest: String)(handler: Try[CredForProofReqCreated] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletExecutor.runCredentialsForProofReq(proofRequest)}, handler)

  override def createProof(proofRequest: String,
                           usedCredentials: String,
                           schemas: String,
                           credentialDefs: String, revStates: String)
                          (handler: Try[ProofCreated] => Unit): Unit = {
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runCreateProof(proofRequest, usedCredentials, schemas, credentialDefs, revStates)},
      handler
    )
  }

  override def verifyProof(proofRequest: String,
                           proof: String,
                           schemas: String,
                           credentialDefs: String,
                           revocRegDefs: String,
                           revocRegs: String)
                          (handler: Try[ProofVerifResult] => Unit): Unit = {
    runIfAllowed(
      AnonCreds,
      {walletExecutor.runVerifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)},
      handler
    )
  }

  override def signRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletExecutor.runSignRequest(submitterDID, request)}, handler)

  override def multiSignRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit =
    runIfAllowed(AnonCreds, {walletExecutor.runMultiSignRequest(submitterDID, request)}, handler)

}
