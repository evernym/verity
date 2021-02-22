package com.evernym.verity.libindy.wallet

import com.evernym.verity.ExecutionContextProvider.walletFutureExecutionContext
import com.evernym.verity.actor.wallet._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.libindy.wallet.operation_executor.CryptoOpExecutor
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.external_api_access.{InvalidSignType, SignatureResult, WalletAccess}
import com.evernym.verity.util.ParticipantUtil
import com.evernym.verity.vault._
import com.evernym.verity.vault.wallet_api.WalletAPI

import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class WalletAccessAPI(protected val appConfig: AppConfig,
                      protected val walletApi: WalletAPI,
                      protected val selfParticipantId: ParticipantId,
                      startAsyncBehavior: => Unit,
                      endAsyncBehavior: => Unit
                     )
                     (implicit val wap: WalletAPIParam)
  extends WalletAccess
    with AnonCredRequestsAPI {

  import com.evernym.verity.protocol.engine.external_api_access.WalletAccess._

  protected def executeAsyncOperation[T](f: => Future[T])(handler: Try[T] => Unit): Unit = {
    startAsyncBehavior
    f onComplete { result =>
      handler(result)
      endAsyncBehavior
    }
  }

  protected def executeAsyncWalletApi[T](cmd: Any)(handler: Try[T] => Unit): Unit = {
    executeAsyncOperation {
      walletApi.executeAsync[T](cmd)
    }(handler)
  }

  override def newDid(keyType: KeyType)(handler: Try[(DID, VerKey)] => Unit): Unit = {
    executeAsyncWalletApi[NewKeyCreated](CreateDID(keyType)) { result =>
      handler(result.map(r => (r.did, r.verKey)))
    }
  }

  override def verKey(forDID: DID)(handler: Try[VerKey] => Unit): Unit =
    executeAsyncWalletApi[VerKey](GetVerKey(forDID))(handler)

  override def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                   (handler: Try[SignatureResult] => Unit): Unit = {
    // currently only one sign type is supported
    if (signType != SIGN_ED25519_SHA512_SINGLE)
      handler(Failure(InvalidSignType(signType)))
    else {
      executeAsyncOperation {
        for {
          verKey <- getVerKeyFromParticipantId(selfParticipantId)
          signed <- walletApi.executeAsync[Array[Byte]](SignMsg(KeyParam.fromVerKey(verKey), msg))
        } yield SignatureResult(signed, verKey)
      }(handler)
    }
  }

  override def signRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit = {
    val ledgerRequest = LedgerRequest(request)
    val submitter = Submitter(submitterDID, Some(wap))

    executeAsyncOperation {
      walletApi.executeAsync[LedgerRequest](SignLedgerRequest(ledgerRequest, submitter))(submitter.wapReq)
    } (handler)
  }

  override def multiSignRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit = {
    val ledgerRequest = LedgerRequest(request)
    val submitter = Submitter(submitterDID, Some(wap))

    executeAsyncOperation {
      walletApi.executeAsync[LedgerRequest](MultiSignLedgerRequest(ledgerRequest, submitter))(submitter.wapReq)
    } (handler)
  }

  override def verify(signer: ParticipantId,
                      msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: Option[VerKey] = None,
                      signType: SignType = SIGN_ED25519_SHA512_SINGLE)
                     (handler: Try[Boolean] => Unit): Unit = {
    // currently only one sign type is supported
    if (signType != SIGN_ED25519_SHA512_SINGLE)
      handler(Failure(InvalidSignType(signType)))
    else {
      executeAsyncOperation {
        getVerKeyFromParticipantId(signer)
      } {
        case Success(verKey) =>
          if (verKeyUsed.getOrElse(verKey).contains(verKey)) {
            verify(msg, sig, verKey, signType)(handler)
          } else {
            handler(Success(false))
          }
        case Failure(exception) => handler(Failure(exception))
      }
    }
  }

  override def verify(msg: Array[Byte],
                      sig: Array[Byte],
                      verKeyUsed: VerKey,
                      signType: SignType)
                     (handler: Try[Boolean] => Unit): Unit = {
  // libindy currently supports only one VerKey per DID
  // we check the VerKey used belongs to the party who signed the message.
    executeAsyncOperation{
      val toVerify = VerifySigByVerKey(verKeyUsed, msg, sig)
      CryptoOpExecutor.verifySig(toVerify)
    } { result =>
      handler(result.map(_.verified))
    }
  }

  def getVerKeyFromParticipantId(participantId: ParticipantId): Future[VerKey] = {
    val did = ParticipantUtil.DID(participantId)
    walletApi.executeAsync[VerKey](GetVerKey(did))
  }

  override def storeTheirDid(did: DID, verKey: VerKey)(handler: Try[TheirKeyStored] => Unit): Unit =
    executeAsyncWalletApi[TheirKeyStored](StoreTheirKey(did, verKey))(handler)

}
