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
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.agent.PayloadMetadata
import com.evernym.verity.actor.base.CoreActor
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.util.UtilBase
import com.evernym.verity.vault.WalletUtil._
import com.evernym.verity.vault.service.{WalletMsgHandler, WalletMsgParam, WalletParam}
import com.evernym.verity.vault.{KeyInfo, WalletExt, WalletNotOpened, WalletProvider}
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future


class WalletActor(val appConfig: AppConfig, util: UtilBase, poolManager: LedgerPoolConnManager)
  extends CoreActor
    with Stash {

  override def receiveCmd: Receive = initReceiveCmd

  def initReceiveCmd: Receive = {
    case swp: SetWalletParam  =>
      walletParamOpt = Option(swp.wp)
      wmpOpt = Option(WalletMsgParam(walletProvider, swp.wp, util, poolManager))
      openWalletIfExists()

    case sw: SetWallet =>
      sw.wallet match {
        case Some(w) =>
          walletExtOpt = Option(w)
          setNewReceiveBehaviour(openedWalletReceiver)
        case None =>
          setNewReceiveBehaviour(postInitReceiver)
      }
      unstashAll()

    case _: WalletCommand => stash()
  }

  def postInitReceiver: Receive = {
    case CreateWallet =>
      walletExtOpt = Option(WalletMsgHandler.handleCreateAndOpenWallet())
      sender ! WalletCreated
      setNewReceiveBehaviour(openedWalletReceiver)
      unstashAll()

    case _: WalletCommand => stash()
  }

  def openedWalletReceiver: Receive = {
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

  def openWalletIfExists(): Unit = {
    runWithInternalSpan(s"openWallet", "WalletActor") {
      walletProvider.openAsync(
        walletParam.walletName, walletParam.encryptionKey, walletParam.walletConfig)
        .map(w => SetWallet(Option(w)))
        .recover {
          case _: WalletNotOpened =>  SetWallet(None)
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
trait WalletCommand extends ActorMessage

case class SetWalletParam(wp: WalletParam) extends WalletCommand
case class SetWallet(wallet: Option[WalletExt]) extends WalletCommand

case object CreateWallet extends WalletCommand

case class CreateNewKey(DID: Option[DID] = None, seed: Option[String] = None) extends WalletCommand {
  override def toString: String = {
    val redacted = seed.map(_ => "redacted")
    s"CreateNewKey($DID, $redacted)"
  }
}

case class CreateDID(keyType: String) extends WalletCommand

case class StoreTheirKey(theirDID: DID, theirDIDVerKey: VerKey, ignoreIfAlreadyExists: Boolean=false)
  extends WalletCommand

case class GetVerKeyOpt(keyInfo: KeyInfo) extends WalletCommand

case class GetVerKey(keyInfo: KeyInfo) extends WalletCommand

case class SignMsg(keyInfo: KeyInfo, msg: Array[Byte]) extends WalletCommand

case class VerifySigByKeyInfo(keyInfo: KeyInfo, challenge: Array[Byte], signature: Array[Byte])
  extends WalletCommand

case class VerifySigByVerKey(verKey: VerKey, challenge: Array[Byte], signature: Array[Byte])
  extends WalletCommand

case class PackMsg(msg: Array[Byte], recipVerKeys: Set[KeyInfo], senderVerKey: Option[KeyInfo])
  extends WalletCommand

case class UnpackMsg(msg: Array[Byte]) extends WalletCommand {
  override def toString: DID = s"UnpackMsg: " + new String(msg)
}

case class LegacyPackMsg(msg: Array[Byte], recipVerKeys: Set[KeyInfo], senderVerKey: Option[KeyInfo])
  extends WalletCommand

case class LegacyUnpackMsg(msg: Array[Byte], fromVerKey: Option[KeyInfo], isAnonCryptedMsg: Boolean)
  extends WalletCommand

case class CreateMasterSecret(masterSecretId: String) extends WalletCommand {
  override def toString: DID = s"CreateMasterSecret(masterSecretId: redacted)"
}

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

case class CredForProofReq(proofRequest: String) extends WalletCommand

case class CreateProof(proofRequest: String, usedCredentials: String, schemas: String,
                       credentialDefs: String, revStates: String, masterSecret: String)
  extends WalletCommand

case class SignLedgerRequest(request: LedgerRequest, submitterDetail: Submitter) extends WalletCommand

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

case class WalletCmdErrorResponse(sd: StatusDetail) extends ActorMessage