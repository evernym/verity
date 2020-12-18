package com.evernym.verity.libindy.wallet

import com.evernym.verity.actor.wallet.{SignLedgerRequest, SignMsg, StoreTheirKey, VerifySigByVerKey}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.ParticipantUtil
import com.evernym.verity.vault._

import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class WalletAccessLibindy(protected val appConfig: AppConfig,
                          protected val walletApi: WalletAPI,
                          protected val selfParticipantId: ParticipantId)
                         (implicit val wap: WalletAPIParam)
  extends WalletAccess with AnonCredRequestsApi {

  import WalletAccess._

  private val maxWaitTime: FiniteDuration = 15 second

  override def newDid(keyType: KeyType): Try[(DID, VerKey)] = {
    Try{
      val created = walletApi.createDID(keyType)
      created.did -> created.verKey
    }
  }

  def verKey(forDID: DID): Try[VerKey] = {
    Try {
      walletApi.getVerKey(GetVerKeyByKeyInfoParam(KeyInfo(Right(GetVerKeyByDIDParam(forDID, getKeyFromPool=false)))))
    }
  }

  override def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE): Try[SignatureResult] = {
    // currently only one sign type is supported
    if (signType != SIGN_ED25519_SHA512_SINGLE)
      return Failure(InvalidSignType(signType))

    Try {
      val verKey = getVerKeyFromParticipantId(selfParticipantId)
      val toSign = SignMsg(KeyInfo(Left(verKey)), msg)
      val signed = walletApi.signMsg(toSign)

      SignatureResult(signed, verKey)
    }
  }

  override def signRequest(submitterDID: DID, request: String): Try[LedgerRequest] = {
    val ledgerRequest = LedgerRequest(request)
    val submitter = Submitter(submitterDID, Some(wap))

    Try(Await.result(
      walletApi.signLedgerRequest(SignLedgerRequest(ledgerRequest, submitter)),
      maxWaitTime
    ))
  }

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKey] = None,
                      signType: SignType = SIGN_ED25519_SHA512_SINGLE): Try[Boolean] = {
    // currently only one sign type is supported
    if (signType != SIGN_ED25519_SHA512_SINGLE)
      return Failure(InvalidSignType(signType))

    val verKey = getVerKeyFromParticipantId(signer)
    if (!verKeyUsed.getOrElse(verKey).contains(verKey)) return Success(false)

    verify(msg, sig, verKey, signType)
  }

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKey,
                      signType: SignType): Try[Boolean] = {
  // libindy currently supports only one VerKey per DID
  // we check the VerKey used belongs to the party who signed the message.
    Try({
      val toVerify = VerifySigByVerKey(verKeyUsed, msg, sig)
      walletApi.verifySigWithVerKey(toVerify).verified
    })
  }

  def getVerKeyFromParticipantId(participantId: ParticipantId): VerKey = {
    val did = ParticipantUtil.DID(participantId)
    val key = GetVerKeyByKeyInfoParam(KeyInfo(Right(GetVerKeyByDIDParam(did, getKeyFromPool=false))))
    walletApi.getVerKey(key)
  }

  override def storeTheirDid(did: DID, verKey: VerKey): Try[Unit] = {
    Try(walletApi.storeTheirKey(StoreTheirKey(did, verKey)))
  }

}
