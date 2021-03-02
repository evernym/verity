package com.evernym.verity.protocol.container.asyncapis.wallet

import com.evernym.verity.actor.wallet._
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.wallet.{InvalidSignType, WalletAccess}
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
  extends WalletAccess
    with AnonCredRequestsAPI
    with BaseAsyncOpExecutorImpl {

  import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess._

  override def newDid(keyType: KeyType)(handler: Try[NewKeyCreated] => Unit): Unit = {
    withAsyncOpRunner({ walletApi.tell(CreateDID(keyType)) }, handler)
  }

  override def storeTheirDid(did: DID, verKey: VerKey)(handler: Try[TheirKeyStored] => Unit): Unit = {
    withAsyncOpRunner(
      { walletApi.tell(StoreTheirKey(did, verKey)) },
      handler
    )
  }
  
  override def verKey(forDID: DID)(handler: Try[GetVerKeyResp] => Unit): Unit = {
    withAsyncOpRunner({ walletApi.tell(GetVerKey(forDID)) }, handler)
  }

  override def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                   (handler: Try[SignedMsg] => Unit): Unit = {
    // currently only one sign type is supported
    if (signType != SIGN_ED25519_SHA512_SINGLE)
      withAsyncOpRunner( { Future.failed(InvalidSignType(signType))}, handler)
    else {
      withAsyncOpRunner(
        {
          val did = getDIDFromParticipantId(selfParticipantId)
          walletApi.tell(SignMsg(KeyParam.fromDID(did), msg))
        },
        handler)
    }
  }

  override def signRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit = {
    val ledgerRequest = LedgerRequest(request)
    val submitter = Submitter(submitterDID, Some(wap))
    withAsyncOpRunner(
      { walletApi.tell(SignLedgerRequest(ledgerRequest, submitter))(submitter.wapReq, senderActorRef) },
      handler
    )
  }

  override def multiSignRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit = {
    val ledgerRequest = LedgerRequest(request)
    val submitter = Submitter(submitterDID, Some(wap))
    withAsyncOpRunner(
      { walletApi.tell(MultiSignLedgerRequest(ledgerRequest, submitter))(submitter.wapReq, senderActorRef) },
      handler
    )
  }

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKey] = None,
                      signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                     (handler: Try[VerifySigResult] => Unit): Unit = {
    // currently only one sign type is supported
    if (signType != SIGN_ED25519_SHA512_SINGLE) {
      withAsyncOpRunner({Future.failed(InvalidSignType(signType))}, handler)
    } else {
      withAsyncOpRunner(
        {
          walletApi.tell(VerifySignature(KeyParam.fromDID(signer), msg, sig, verKeyUsed))
        },
        handler
      )
    }
  }

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKey,
                      signType: SignType)
                     (handler: Try[VerifySigResult] => Unit): Unit = {
  // libindy currently supports only one VerKey per DID
  // we check the VerKey used belongs to the party who signed the message.
    withAsyncOpRunner(
      { walletApi.tell(VerifySignature(KeyParam.fromVerKey(verKeyUsed), msg, sig)) },
      handler
    )
  }

  private def getDIDFromParticipantId(participantId: ParticipantId): DID = {
    ParticipantUtil.DID(participantId)
  }
}
