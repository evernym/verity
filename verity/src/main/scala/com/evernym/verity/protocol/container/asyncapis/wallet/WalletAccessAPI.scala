package com.evernym.verity.protocol.container.asyncapis.wallet

import com.evernym.verity.actor.wallet._
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.BaseAsyncOpExecutorImpl
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.AsyncResultHandler
import com.evernym.verity.protocol.engine.asyncapi.wallet._
import com.evernym.verity.util.ParticipantUtil
import com.evernym.verity.vault._
import com.evernym.verity.vault.wallet_api.WalletAPI

import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Try

class WalletAccessAPI(protected val walletApi: WalletAPI,
                      protected val selfParticipantId: ParticipantId)
                     (implicit val wap: WalletAPIParam,
                      val asyncAPIContext: AsyncAPIContext)
  extends WalletAsyncOps
    with AnonCredRequestsAPI
    with BaseAsyncOpExecutorImpl
    with AsyncResultHandler {

  import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess._

  override def DEPRECATED_setupNewWallet(walletId: String, ownerDidPair: DidPair): Unit = {
    walletApi.tell(SetupNewAgentWallet(Option(ownerDidPair)))(WalletAPIParam(walletId), senderActorRef)
  }

  override def runNewDid(keyType: KeyType = KEY_ED25519): Unit = {
    walletApi.tell(CreateDID(keyType))
  }

  override def runStoreTheirDid(did: DidStr, verKey: VerKeyStr, ignoreIfAlreadyExists: Boolean = false): Unit = {
    walletApi.tell(StoreTheirKey(did, verKey, ignoreIfAlreadyExists))
  }
  
  override def runVerKey(forDID: DidStr): Unit = {
    walletApi.tell(GetVerKey(forDID))
  }

  override def runVerKeyOpt(forDID: DidStr): Unit = {
    walletApi.tell(GetVerKeyOpt(forDID))
  }

  override def runSign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE): Unit = {
    // currently only one sign type is supported
    if (signType != SIGN_ED25519_SHA512_SINGLE)
      Future.failed(InvalidSignType(signType))
    else {
      val did = getDIDFromParticipantId(selfParticipantId)
      walletApi.tell(SignMsg(KeyParam.fromDID(did), msg))
    }
  }

  override def runSignRequest(submitterDID: DidStr, request: String): Unit = {
    val ledgerRequest = LedgerRequest(request)
    val submitter = Submitter(submitterDID, Some(wap))
    walletApi.tell(SignLedgerRequest(ledgerRequest, submitter))(submitter.wapReq, senderActorRef)
  }

  override def runMultiSignRequest(submitterDID: DidStr, request: String): Unit = {
    val ledgerRequest = LedgerRequest(request)
    val submitter = Submitter(submitterDID, Some(wap))
    walletApi.tell(MultiSignLedgerRequest(ledgerRequest, submitter))(submitter.wapReq, senderActorRef)
  }

  override def runVerify(signer: ParticipantId,
                         msg: Array[Byte],
                         sig: Array[Byte],
                         verKeyUsed: Option[VerKeyStr] = None,
                         signType: SignType = SIGN_ED25519_SHA512_SINGLE): Unit = {
    // currently only one sign type is supported
    if (signType != SIGN_ED25519_SHA512_SINGLE) {
      Future.failed(InvalidSignType(signType))
    } else {
      walletApi.tell(VerifySignature(KeyParam.fromDID(signer), msg, sig, verKeyUsed))
    }
  }

  override def runVerify(msg: Array[Byte],
                         sig: Array[Byte],
                         verKeyUsed: VerKeyStr,
                         signType: SignType): Unit = {
  // libindy currently supports only one VerKey per DID
  // we check the VerKey used belongs to the party who signed the message.
    walletApi.tell(VerifySignature(KeyParam.fromVerKey(verKeyUsed), msg, sig))
  }

  private def getDIDFromParticipantId(participantId: ParticipantId): DidStr = {
    ParticipantUtil.DID(participantId)
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
