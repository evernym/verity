package com.evernym.verity.actor.wallet

import akka.pattern.pipe
import akka.actor.{ActorRef, Stash}
import com.evernym.verity.Exceptions.HandledErrorException
import com.evernym.verity.Status.UNHANDLED
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerRequest, Submitter}
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.ExecutionContextProvider.walletFutureExecutionContext
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.agent.PayloadMetadata
import com.evernym.verity.actor.base.CoreActor
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault.WalletUtil._
import com.evernym.verity.vault.service.{WalletMsgHandler, WalletMsgParam, WalletParam}
import com.evernym.verity.vault.{KeyParam, WalletExt, WalletNotOpened, WalletProvider}
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future


class WalletActor(val appConfig: AppConfig, poolManager: LedgerPoolConnManager)
  extends CoreActor
    with Stash {

  override def receiveCmd: Receive = preInitReceiver orElse openWalletCallbackReceiver

  /**
   * during actor 'preStart' lifecycle hook, it asynchronously calls 'generateWalletParamAsync'
   * and as part of callback it sends 'SetWalletParam' command back to this actor
   * to update the state accordingly
   * @return
   */
  def preInitReceiver: Receive = {
    case swp: SetWalletParam  =>
      walletParamOpt = Option(swp.wp)
      wmpOpt = Option(WalletMsgParam(walletProvider, swp.wp, Option(poolManager)))
      tryOpeningWalletIfExists()
  }

  /**
   * in this receiver it will only entertain 'CreateWallet' command
   * @return
   */
  def postInitReceiver: Receive = {
    case CreateWallet =>
      val sndr = sender()
      WalletMsgHandler.handleCreateWalletASync().map { resp =>
        sndr ! resp
        tryOpeningWalletIfExists()
      }
  }

  /**
   * as part of 'openWalletIfExists' function call, it calls open wallet async api
   * and as part of it's callback, it sends 'SetWallet' command back to this actor
   * to update the state accordingly
   * @return
   */
  def openWalletCallbackReceiver: Receive = {
    case sw: SetWallet =>
      sw.wallet match {
        case Some(w) =>
          walletExtOpt = Option(w)
          setNewReceiveBehaviour(postOpenWalletReceiver)
        case None =>
          setNewReceiveBehaviour(postInitReceiver orElse openWalletCallbackReceiver)
      }
      unstashAll()
    case _: WalletCommand => stash()
  }

  /**
   * will entertain wallet commands only if wallet is already opened
   * @return
   */
  def postOpenWalletReceiver: Receive = {
    case CreateWallet if walletExtOpt.isDefined =>
      sender ! WalletAlreadyCreated

    case cmd: WalletCommand if walletExtOpt.isDefined =>    //only entertain commands extending 'WalletCommand'
      val sndr = sender()
      handleRespFut(sndr, WalletMsgHandler.executeAsync(cmd))
  }

  def handleRespFut(sndr: ActorRef, fut: Future[Any]): Unit = {
    fut.recover {
      case e: HandledErrorException =>
        WalletCmdErrorResponse(StatusDetail(e.respCode, e.responseMsg))
      case e: Exception =>
        WalletCmdErrorResponse(UNHANDLED.copy(statusMsg = e.getMessage))
    }.pipeTo(sndr)
  }

  def tryOpeningWalletIfExists(): Unit = {
    setNewReceiveBehaviour(openWalletCallbackReceiver)
    openWalletIfExists()
  }

  def openWalletIfExists(): Unit = {
    runWithInternalSpan(s"openWallet", "WalletActor") {
      walletProvider.openAsync(
        walletParam.walletName, walletParam.encryptionKey, walletParam.walletConfig)
        .map(w => SetWallet(Option(w)))
        .recover {
          case _: WalletNotOpened => SetWallet(None)
        }.pipeTo(self)
    }
  }

  override def beforeStart(): Unit = {
    generateWalletParamAsync(entityId, appConfig, walletProvider).map { wp =>
      self ! SetWalletParam(wp)
    }
  }

  override def afterStop(): Unit = {
    closeWallet()
  }

  def closeWallet(): Unit = {
    if (walletExtOpt.isEmpty) {
      logger.debug("WalletActor try to close not opened wallet")
    } else {
      runWithInternalSpan(s"closeWallet", "WalletActor") {
        walletProvider.close(walletExt)
      }
    }
  }


  val logger: Logger = getLoggerByClass(classOf[WalletActor])
  lazy val walletProvider: WalletProvider = new LibIndyWalletProvider(appConfig)

  var walletExtOpt: Option[WalletExt] = None
  var walletParamOpt: Option[WalletParam] = None
  var wmpOpt: Option[WalletMsgParam] = None

  implicit def walletExt: WalletExt = walletExtOpt.getOrElse(
    throw new RuntimeException("wallet not opened"))
  implicit lazy val walletParam: WalletParam = walletParamOpt.getOrElse(
    throw new RuntimeException("wallet param not computed"))
  implicit lazy val wmp: WalletMsgParam = wmpOpt.getOrElse(
    throw new RuntimeException("wallet param not computed"))

}

//command
trait WalletCommand extends ActorMessage {
  //overridden to make sure if this codebase is logging these commands anywhere
  //it doesn't log any critical/private information
  override def toString: DID = this.getClass.getSimpleName
}

case class SetWalletParam(wp: WalletParam) extends WalletCommand

case class SetWallet(wallet: Option[WalletExt]) extends WalletCommand

case object CreateWallet extends WalletCommand

case class CreateNewKey(DID: Option[DID] = None, seed: Option[String] = None) extends WalletCommand

case class CreateDID(keyType: String) extends WalletCommand

case class StoreTheirKey(theirDID: DID, theirDIDVerKey: VerKey, ignoreIfAlreadyExists: Boolean=false)
  extends WalletCommand

case class GetVerKeyOpt(keyParam: KeyParam) extends WalletCommand

case class GetVerKey(keyParam: KeyParam) extends WalletCommand

case class SignMsg(keyParam: KeyParam, msg: Array[Byte]) extends WalletCommand

case class VerifySigByKeyParam(keyParam: KeyParam, challenge: Array[Byte], signature: Array[Byte])
  extends WalletCommand

case class VerifySigByVerKey(verKey: VerKey, challenge: Array[Byte], signature: Array[Byte])
  extends WalletCommand

case class PackMsg(msg: Array[Byte], recipVerKeyParams: Set[KeyParam], senderVerKeyParam: Option[KeyParam])
  extends WalletCommand

case class UnpackMsg(msg: Array[Byte]) extends WalletCommand {
  override def toString: String = s"UnpackMsg: " + new String(msg)
}

case class LegacyPackMsg(msg: Array[Byte], recipVerKeyParams: Set[KeyParam], senderVerKeyParam: Option[KeyParam])
  extends WalletCommand

case class LegacyUnpackMsg(msg: Array[Byte], fromVerKeyParam: Option[KeyParam], isAnonCryptedMsg: Boolean)
  extends WalletCommand

case class CreateMasterSecret(masterSecretId: String) extends WalletCommand

case class CreateCredDef(issuerDID: DID,
                         schemaJson: String,
                         tag: String,
                         sigType: Option[String],
                         revocationDetails: Option[String]) extends WalletCommand

case class CreateCredOffer(credDefId: String) extends WalletCommand

case class CreateCredReq(credDefId: String, proverDID: DID,
                         credDefJson: String, credOfferJson: String, masterSecretId: String)
  extends WalletCommand

case class CreateCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                      revRegistryId: String, blobStorageReaderHandle: Int)
  extends WalletCommand

case class StoreCred(credId: String, credDefJson: String,
                     credReqMetadataJson: String, credJson: String, revRegDefJson: String)
  extends WalletCommand

case class CredForProofReq(proofRequest: String) extends WalletCommand

case class CreateProof(proofRequest: String, requestedCredentials: String, schemas: String,
                       credentialDefs: String, masterSecret: String, revStates: String)
  extends WalletCommand

case class SignLedgerRequest(request: LedgerRequest, submitterDetail: Submitter) extends WalletCommand

case class MultiSignLedgerRequest(request: LedgerRequest, submitterDetail: Submitter) extends WalletCommand

//responses
trait WalletCmdSuccessResponse extends ActorMessage

trait WalletCreatedBase extends WalletCmdSuccessResponse
case object WalletCreated extends WalletCreatedBase
case object WalletAlreadyCreated extends WalletCreatedBase

case class NewKeyCreated(did: DID, verKey: VerKey) extends WalletCmdSuccessResponse

case class TheirKeyStored(did: DID, verKey: VerKey) extends WalletCmdSuccessResponse

case class VerifySigResult(verified: Boolean) extends WalletCmdSuccessResponse

case class PackedMsg(msg: Array[Byte], metadata: Option[PayloadMetadata]=None)
  extends WalletCmdSuccessResponse

object UnpackedMsg {
  def apply(msg: String, senderVerKey: Option[VerKey] = None, recipVerKey: Option[VerKey]=None): UnpackedMsg =
    UnpackedMsg(msg.getBytes, senderVerKey, recipVerKey)
}
case class UnpackedMsg(msg: Array[Byte],
                       senderVerKey: Option[VerKey],
                       recipVerKey: Option[VerKey]) extends WalletCmdSuccessResponse {
  def msgString: String = new String(msg)
}

case class CreatedCredDef(credDefId: String, credDefJson: String) extends WalletCmdSuccessResponse

case class CreatedCredReq(credReqJson: String, credReqMetadataJson: String) extends WalletCmdSuccessResponse

case class WalletCmdErrorResponse(sd: StatusDetail) extends ActorMessage