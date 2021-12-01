package com.evernym.verity.protocol.engine.asyncapi.wallet

import com.evernym.verity.actor.wallet._
import com.evernym.verity.config.ConfigConstants.SALT_WALLET_NAME
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.BaseAsyncAccessImpl
import com.evernym.verity.protocol.engine.ParticipantId
import com.evernym.verity.protocol.engine.asyncapi.{AsyncOpRunner, AsyncResultHandler, BaseAccessController}
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil._
import com.evernym.verity.util.{HashUtil, ParticipantUtil, Util}
import com.evernym.verity.vault.operation_executor.{AnoncredsWalletOpExecutor, FutureConverter}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import com.evernym.vdrtools.anoncreds.Anoncreds.issuerCreateSchema
import com.evernym.vdrtools.anoncreds.DuplicateMasterSecretNameException

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

class WalletAccessAdapter(protected val walletApi: WalletAPI,
                          protected val selfParticipantId: ParticipantId)
                         (implicit val ec: ExecutionContext,
                          implicit val asyncOpRunner: AsyncOpRunner,
                          implicit val wap: WalletAPIParam,
                          val asyncAPIContext: AsyncAPIContext)

  extends WalletAccess
    with BaseAccessController
    with BaseAsyncAccessImpl
    with AsyncResultHandler
    with FutureConverter {

  import WalletAccess._

  def handleAsyncOpResult[T](handler: Try[T] => Unit): Try[T] => Unit = {
    {t: Try[_] => handleResult(t, handler)}
  }

  def DEPRECATED_setupNewWallet(walletId: String,
                                ownerDidPair: DidPair)
                               (handler: Try[DeprecatedWalletSetupResult] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(SetupNewAgentWallet(Option(ownerDidPair)))(WalletAPIParam(walletId), senderActorRef)},
      handleAsyncOpResult(handler)
    )

  override def newDid(keyType: KeyType = KEY_ED25519)(handler: Try[NewKeyResult] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(CreateDID(keyType))},
      handleAsyncOpResult(handler)
    )

  override def verKey(forDID: DidStr)(handler: Try[VerKeyResult] => Unit): Unit = {
    withAsyncOpRunner(
      {walletApi.tell(GetVerKey(forDID))},
      handleAsyncOpResult(handler)
    )
  }

  override def verKeyOpt(forDID: DidStr)(handler: Try[VerKeyOptResult] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(GetVerKeyOpt(forDID))},
      handleAsyncOpResult(handler)
    )

  override def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                   (handler: Try[SignedMsgResult] => Unit): Unit = {
    // currently only one sign type is supported
    if (signType != SIGN_ED25519_SHA512_SINGLE) {
      handleAsyncOpResult(handler)(Failure(InvalidSignType(signType)))
    } else {
      withAsyncOpRunner(
        {runSign(msg)},
        handleAsyncOpResult(handler)
      )
    }
  }

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKeyStr] = None,
                      signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                     (handler: Try[VerifiedSigResult] => Unit): Unit = {
    // currently only one sign type is supported
    if (signType != SIGN_ED25519_SHA512_SINGLE) {
      handleAsyncOpResult(handler)(Failure(InvalidSignType(signType)))
    } else {
      withAsyncOpRunner(
        {walletApi.tell(VerifySignature(KeyParam.fromDID(signer), msg, sig, verKeyUsed))},
        handleAsyncOpResult(handler)
      )
    }
  }

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKeyStr,
                      signType: SignType)
                     (handler: Try[VerifiedSigResult] => Unit): Unit =
    withAsyncOpRunner(
      // libindy currently supports only one VerKey per DID
      // we check the VerKey used belongs to the party who signed the message.
      {walletApi.tell(VerifySignature(KeyParam.fromVerKey(verKeyUsed), msg, sig))},
      handleAsyncOpResult(handler)
    )


  override def storeTheirDid(did: DidStr,
                             verKey: VerKeyStr,
                             ignoreIfAlreadyExists: Boolean = false)
                            (handler: Try[TheirKeyStoredResult] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(StoreTheirKey(did, verKey, ignoreIfAlreadyExists))},
      handleAsyncOpResult(handler)
    )

  override def createSchema(issuerDID:  DidStr,
                            name:  String,
                            version:  String,
                            data:  String)
                           (handler: Try[SchemaCreatedResult] => Unit): Unit =
    withFutureOpRunner(
      {issuerCreateSchema(issuerDID, name, version, data).map { result =>
        SchemaCreated(result.getSchemaId, result.getSchemaJson)
      }},
      handleAsyncOpResult(handler)
    )

  override def createCredDef(issuerDID: DidStr,
                             schemaJson: String,
                             tag: String,
                             sigType:  Option[String],
                             revocationDetails: Option[String])
                            (handler: Try[CredDefCreatedResult] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(CreateCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails))},
      handleAsyncOpResult(handler)
    )

  override def createCredOffer(credDefId: String)(handler: Try[CredOfferCreatedResult] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(CreateCredOffer(credDefId))},
      handleAsyncOpResult(handler)
    )

  override def createCredReq(credDefId: String,
                             proverDID: DidStr,
                             credDefJson: String,
                             credOfferJson: String)
                            (handler: Try[CredReqCreatedResult] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(CreateCredReq(credDefId, proverDID, credDefJson, credOfferJson, masterSecretId))},
      handleAsyncOpResult(handler)
    )

  override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                          revRegistryId: String, blobStorageReaderHandle: Int)
                         (handler: Try[CredCreatedResult] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(CreateCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle))},
      handleAsyncOpResult(handler)
    )

  override def storeCred(credId: String,
                         credDefJson: String,
                         credReqMetadataJson: String,
                         credJson: String,
                         revRegDefJson: String)
                        (handler: Try[CredStoredResult] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(StoreCred(credId, credReqMetadataJson, credJson, credDefJson, revRegDefJson))},
      handleAsyncOpResult(handler)
    )

  override def credentialsForProofReq(proofRequest: String)(handler: Try[CredForProofResult] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(CredForProofReq(proofRequest))},
      handleAsyncOpResult(handler)
    )

  override def createProof(proofRequest: String,
                           usedCredentials: String,
                           schemas: String,
                           credentialDefs: String, revStates: String)
                          (handler: Try[ProofCreatedResult] => Unit): Unit = {
    withAsyncOpRunner(
      {walletApi.tell(CreateProof(proofRequest, usedCredentials, schemas, credentialDefs, masterSecretId, revStates))},
      handleAsyncOpResult(handler)
    )
  }

  override def verifyProof(proofRequest: String,
                           proof: String,
                           schemas: String,
                           credentialDefs: String,
                           revocRegDefs: String,
                           revocRegs: String)
                          (handler: Try[ProofVerificationResult] => Unit): Unit = {
    withFutureOpRunner(
      {AnoncredsWalletOpExecutor.verifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)},
      handleAsyncOpResult(handler)
    )
  }

  override def signRequest(submitterDID: DidStr,
                           request: String)
                          (handler: Try[LedgerRequestResult] => Unit): Unit =
    withAsyncOpRunner(
      {runSignRequest(submitterDID, request)},
      handleAsyncOpResult(handler)
    )

  override def multiSignRequest(submitterDID: DidStr,
                                request: String)
                               (handler: Try[LedgerRequestResult] => Unit): Unit =
    withAsyncOpRunner(
      {runMultiSignRequest(submitterDID, request)},
      handleAsyncOpResult(handler)
    )

  private def getDIDFromParticipantId(participantId: ParticipantId): DidStr = {
    ParticipantUtil.DID(participantId)
  }

  //Allowed only for signType: SignType = SIGN_ED25519_SHA512_SINGLE
  private def runSign(msg: Array[Byte]): Unit = {
      val did = getDIDFromParticipantId(selfParticipantId)
      walletApi.tell(SignMsg(KeyParam.fromDID(did), msg))
  }

  private def runSignRequest(submitterDID: DidStr, request: String): Unit = {
    val ledgerRequest = LedgerRequest(request)
    val submitter = Submitter(submitterDID, Some(wap))
    walletApi.tell(SignLedgerRequest(ledgerRequest, submitter))(submitter.wapReq, senderActorRef)
  }

  private def runMultiSignRequest(submitterDID: DidStr, request: String): Unit = {
    val ledgerRequest = LedgerRequest(request)
    val submitter = Submitter(submitterDID, Some(wap))
    walletApi.tell(MultiSignLedgerRequest(ledgerRequest, submitter))(submitter.wapReq, senderActorRef)
  }

  //AnonCredRequestsAPI
  lazy val masterSecretId: String = {

    val salt = appConfig.getStringReq(SALT_WALLET_NAME)
    val msIdHex = HashUtil.hash(SHA256)(selfParticipantId + salt).hex
    //TODO: may want to optimize this (for now, every time a cred request is sent, it will do below check)
    Try(Util.DEPRECATED_convertToSyncReq(walletApi.executeAsync[MasterSecretCreated](CreateMasterSecret(msIdHex)))) match {
      case Success(msc) if msc.ms == msIdHex => msIdHex
      case Failure(_: DuplicateMasterSecretNameException) => msIdHex    //already created
      case Failure(_: RuntimeException) => throw new RuntimeException("error during master secret creation")
    }
  }

  override def handleResult[T](result: Try[Any], handler: Try[T] => Unit): Unit = {
    handler(
      result.map {
        case c: AgentWalletSetupCompleted => DeprecatedWalletSetupResult(c.ownerDidPair, NewKeyResult(c.agentKey.did, c.agentKey.verKey))
        case c: NewKeyCreated => NewKeyResult(c.did, c.verKey)
        case c: GetVerKeyResp => VerKeyResult(c.verKey)
        case c: GetVerKeyOptResp => VerKeyOptResult(c.verKey)
        case c: SignedMsg => SignedMsgResult(c.msg, c.fromVerKey)
        case c: VerifySigResult => VerifiedSigResult(c.verified)
        case c: TheirKeyStored => TheirKeyStoredResult(c.did, c.verKey)
        case c: SchemaCreated => SchemaCreatedResult(c.schemaId, c.schemaJson)

        case c: CredDefCreated => CredDefCreatedResult(c.credDefId, c.credDefJson)

        case c: CredOfferCreated => CredOfferCreatedResult(c.offer)
        case c: CredReqCreated => CredReqCreatedResult(c.credReqJson, c.credReqMetadataJson)
        case c: CredCreated => CredCreatedResult(c.cred)
        case c: CredStored => CredStoredResult(c.cred)
        case c: CredForProofReqCreated => CredForProofResult(c.cred)

        case c: ProofCreated => ProofCreatedResult(c.proof)
        case c: ProofVerifResult => ProofVerificationResult(c.result)

        case c: LedgerRequest => LedgerRequestResult(
          c.req,
          c.needsSigning,
          c.taa.map(t=>TransactionAuthorAgreement(
            t.version,
            t.digest,
            t.mechanism,
            t.timeOfAcceptance
          ))
        )
      }
        .map(_.asInstanceOf[T])
    )
  }
}

case class SchemaCreated(schemaId: String, schemaJson: String)