package com.evernym.verity.actor.wallet

import akka.actor.{ActorRef, NoSerializationVerificationNeeded, Stash}
import akka.pattern.pipe
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.PayloadMetadata
import com.evernym.verity.actor.base.CoreActor
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerRequest, Submitter}
import com.evernym.verity.vdrtools.wallet.LibIndyWalletProvider
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.observability.metrics.InternalSpan
import com.evernym.verity.protocol.engine.asyncapi.wallet.SignatureResult
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.util2.Status.{INVALID_VALUE, StatusDetail, UNHANDLED}
import com.evernym.verity.vault
import com.evernym.verity.vault.WalletUtil._
import com.evernym.verity.vault.operation_executor.DidOpExecutor.buildErrorDetail
import com.evernym.verity.vault.service.{WalletMsgHandler, WalletMsgParam, WalletParam}
import com.evernym.verity.vault.{KeyParam, WalletDoesNotExist, WalletExt, WalletProvider}
import com.evernym.verity.vdr.LedgerPrefix
import com.typesafe.scalalogging.Logger

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}


class WalletActor(val appConfig: AppConfig, poolManager: LedgerPoolConnManager, executionContext: ExecutionContext)
  extends CoreActor
    with Stash {

  implicit lazy val futureExecutionContext: ExecutionContext = executionContext

  override def receiveCmd: Receive = stateUninitialized

  /**
   * during actor 'preStart' lifecycle hook, it asynchronously calls 'generateWalletParamAsync'
   * and as part of callback it sends 'SetWalletParam' command back to this actor
   * to update the state accordingly
   *
   * @return
   */
  def stateUninitialized: Receive = {
    case swp: SetWalletParam =>
      walletParamOpt = Option(swp.wp)
      wmpOpt = Option(WalletMsgParam(walletProvider, swp.wp, Option(poolManager)))
      openWalletIfExists()
    case sw: SetWallet =>
      handleSetWallet(sw)
    case _: WalletCommand => stash()
  }

  def stateWalletNotOpened: Receive = {
    case cmd: CreateWallet =>
      handleCreateWallet(cmd)
    case snw: SetupNewAgentWallet =>
      handleSetupNewAgentWalletOnStateWalletIsNotOpened(snw)
    case sw: SetWallet =>
      handleSetWallet(sw)
    case _: WalletCommand => stash()
  }

  /**
   * as part of 'openWalletIfExists' function call, it calls open wallet async api
   * and as part of it's callback, it sends 'SetWallet' command back to this actor
   * to update the state accordingly
   */
  def stateWalletIsOpening: Receive = {
    case util.Failure(vault.WalletAlreadyOpened(_)) =>
      setNewReceiveBehaviour(stateWalletNotOpened)
    case sw: SetWallet =>
      handleSetWallet(sw)
    case _: WalletCommand =>
      stash()
  }

  /**
   * will entertain wallet commands only if wallet is already opened
   *
   */
  def stateReady: Receive = {
    case snaw: SetupNewAgentWallet =>
      handleSetupNewAgentWalletOnStateReady(snaw)

    case _: CreateWallet if walletExtOpt.isDefined =>
      sender() ! WalletAlreadyCreated

    case cmd: WalletCommand if walletExtOpt.isDefined => //only entertain commands extending 'WalletCommand'
      val sndr = sender()
      logger.debug(s"[$actorId] [${cmd.id}] wallet op started: " + cmd)
      sendRespWhenResolved(cmd.id, sndr, WalletMsgHandler.executeAsync(cmd))
  }


  private def handleSetWallet(sw: SetWallet): Unit = {
    sw.wallet match {
      case Some(w) =>
        walletExtOpt = Option(w)
        setNewReceiveBehaviour(stateReady)
      case None =>
        setNewReceiveBehaviour(stateWalletNotOpened)
    }
    unstashAll()
  }

  /**
   *there is some code duplicate in two following methods,
   * but state changing looks better inside handle method against a function that combines repeating code
   */

  private def handleCreateWallet(cmd: CreateWallet): Unit = {
    setNewReceiveBehaviour(stateWalletIsOpening)
    val sndr = sender()
    logger.debug(s"[$actorId] [${cmd.id}] wallet op started: " + cmd)
    val fut = WalletMsgHandler.handleCreateWalletAsync()
    sendRespWhenResolved(cmd.id, sndr, fut)
      .map { _ =>
        openWalletIfExists()
      }
  }

  private def handleSetupNewAgentWalletOnStateWalletIsNotOpened(snw: SetupNewAgentWallet): Unit = {
    setNewReceiveBehaviour(stateWalletIsOpening)
    val sndr = sender()
    WalletMsgHandler.handleCreateWalletAsync().map { _ =>
      openWalletIfExists()
      self.tell(snw, sndr)
    }
  }

  private def handleSetupNewAgentWalletOnStateReady(snw: SetupNewAgentWallet): Unit = {
    val sndr = sender()
    logger.debug(s"[$actorId] [${snw.id}] wallet op started: " + snw)
    val ownerKeyFut =
      snw.ownerDidPair match {
        case Some(odp) =>
          WalletMsgHandler
            .executeAsync(StoreTheirKey(odp.did, odp.verKey))
            .mapTo[TheirKeyStored].map(_.didPair)
        case None =>
          WalletMsgHandler
            .executeAsync(CreateNewKey())
            .mapTo[NewKeyCreated].map(_.didPair)
      }

    val fut = for (
      odp <- ownerKeyFut;
      nks <- WalletMsgHandler.executeAsync(CreateNewKey()).mapTo[NewKeyCreated]
    ) yield {
      AgentWalletSetupCompleted(odp, nks)
    }

    sendRespWhenResolved(snw.id, sndr, fut)
  }

  private def sendRespWhenResolved(cmdId: String, sndr: ActorRef, fut: Future[Any]): Future[Any] = {
    withErrorHandling(cmdId, fut)
      .map { r =>
        logger.debug(s"[$actorId] [$cmdId] wallet op finished: " + r)
        r
      }.pipeTo(sndr)
  }

  private def withErrorHandling(id: String, fut: Future[Any]): Future[Any] = {
    fut.recover {
      case e: HandledErrorException if e.respCode == INVALID_VALUE.statusCode =>
        WalletCmdErrorResponse(StatusDetail(e.respCode, e.responseMsg))
      case e: HandledErrorException =>
        logger.info(s"[$actorId] [$id] handled error while wallet operation: " + e.errorDetail.getOrElse(e.getMessage))
        WalletCmdErrorResponse(StatusDetail(e.respCode, e.responseMsg))
      case e: Throwable =>
        logger.error(s"[$actorId] [$id] unhandled error while wallet operation: " + buildErrorDetail(e))
        WalletCmdErrorResponse(UNHANDLED.copy(statusMsg = e.getMessage))
    }
  }

  def openWallet(): Future[WalletExt] = {
    walletProvider.openAsync(
      walletParam.walletName, walletParam.encryptionKey, walletParam.walletConfig)
  }

  def openWalletIfExists(): Unit = {
    metricsWriter.runWithSpan(s"openWallet", "WalletActor", InternalSpan) {
      openWallet()
        .map(w => SetWallet(Option(w)))
        .recover {
          case _@(_: WalletDoesNotExist) =>
            SetWallet(None)
          case e =>
            logger.error(s"[$actorId] unexpected error occurred while trying to open wallet: " + e.getMessage + s" (${e.getClass.getName})")
            throw e
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
      logger.debug(s"[$actorId] WalletActor try to close not opened wallet")
    } else {
      metricsWriter.runWithSpan("closeWallet", "WalletActor", InternalSpan) {
        walletProvider.close(walletExt)
      }
    }
  }


  val logger: Logger = getLoggerByClass(classOf[WalletActor])
  lazy val walletProvider: WalletProvider = LibIndyWalletProvider

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
  //overridden to make sure if this codebase is logging this wallet command anywhere
  // it doesn't log any critical/private information
  val name: String = getClass.getSimpleName
  val id: String = s"${UUID.randomUUID().toString} ($name)"   //used only for logging purposes
}

//NOTE:
// It has been observed that as part of tests, good amount of time
// serialization/deserialization check fails for this case class
// until we find proper solution, lets extend this class from 'NoSerializationVerificationNeeded'
// to avoid the check during test run
case class SetWalletParam(wp: WalletParam) extends WalletCommand with NoSerializationVerificationNeeded

//NOTE:
// It has been observed that as part of tests, good amount of time
// serialization/deserialization check fails for this case class
// until we find proper solution, lets extend this class from 'NoSerializationVerificationNeeded'
// to avoid the check during test run
case class SetWallet(wallet: Option[WalletExt]) extends WalletCommand with NoSerializationVerificationNeeded

case class CreateWallet() extends WalletCommand

case class SetupNewAgentWallet(ownerDidPair: Option[DidPair]) extends WalletCommand

case class CreateNewKey(DID: Option[DidStr] = None, seed: Option[String] = None) extends WalletCommand

case class CreateDID(keyType: String, ledgerPrefix: Option[LedgerPrefix] = None) extends WalletCommand

case class StoreTheirKey(theirDID: DidStr, theirDIDVerKey: VerKeyStr, ignoreIfAlreadyExists: Boolean = false)
  extends WalletCommand

case class GetVerKeyOpt(did: DidStr, getKeyFromPool: Boolean = false) extends WalletCommand

case class GetVerKey(did: DidStr, getKeyFromPool: Boolean = false) extends WalletCommand

case class SignMsg(keyParam: KeyParam, msg: Array[Byte]) extends WalletCommand

case class VerifySignature(keyParam: KeyParam, challenge: Array[Byte],
                           signature: Array[Byte], verKeyUsed: Option[VerKeyStr] = None)
  extends WalletCommand

case class PackMsg(msg: Array[Byte], recipVerKeyParams: Set[KeyParam], senderVerKeyParam: Option[KeyParam])
  extends WalletCommand

case class UnpackMsg(msg: Array[Byte]) extends WalletCommand

case class LegacyPackMsg(msg: Array[Byte], recipVerKeyParams: Set[KeyParam], senderVerKeyParam: Option[KeyParam])
  extends WalletCommand

case class LegacyUnpackMsg(msg: Array[Byte], fromVerKeyParam: Option[KeyParam], isAnonCryptedMsg: Boolean)
  extends WalletCommand

case class CreateMasterSecret(masterSecretId: String) extends WalletCommand

case class CreateCredDef(issuerDID: DidStr,
                         schemaJson: String,
                         tag: String,
                         sigType: Option[String],
                         revocationDetails: Option[String]) extends WalletCommand

case class CreateCredOffer(credDefId: String) extends WalletCommand

case class CreateCredReq(credDefId: String, proverDID: DidStr,
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

trait Reply extends ActorMessage

case class WalletCmdErrorResponse(sd: StatusDetail) extends Reply

trait WalletCmdSuccessResponse extends Reply

case class AgentWalletSetupCompleted(ownerDidPair: DidPair, agentKey: NewKeyCreated) extends WalletCmdSuccessResponse

trait WalletCreatedBase extends WalletCmdSuccessResponse

case object WalletCreated extends WalletCreatedBase

case object WalletAlreadyCreated extends WalletCreatedBase

case class NewKeyCreated(did: DidStr, verKey: VerKeyStr) extends WalletCmdSuccessResponse {
  def didPair: DidPair = DidPair(did, verKey)
}

case class GetVerKeyOptResp(verKey: Option[VerKeyStr]) extends WalletCmdSuccessResponse

case class GetVerKeyResp(verKey: VerKeyStr) extends WalletCmdSuccessResponse

case class TheirKeyStored(did: DidStr, verKey: VerKeyStr) extends WalletCmdSuccessResponse {
  def didPair: DidPair = DidPair(did, verKey)
}

case class VerifySigResult(verified: Boolean) extends WalletCmdSuccessResponse

case class SignedMsg(msg: Array[Byte], fromVerKey: VerKeyStr) extends WalletCmdSuccessResponse {
  def signatureResult: SignatureResult = SignatureResult(msg, fromVerKey)
}

case class MasterSecretCreated(ms: String) extends WalletCmdSuccessResponse

case class CredOfferCreated(offer: String) extends WalletCmdSuccessResponse

case class CredDefCreated(credDefId: String, credDefJson: String) extends WalletCmdSuccessResponse

case class CredReqCreated(credReqJson: String, credReqMetadataJson: String) extends WalletCmdSuccessResponse

case class CredCreated(cred: String) extends WalletCmdSuccessResponse

case class CredStored(credId: String) extends WalletCmdSuccessResponse

case class CredForProofReqCreated(cred: String) extends WalletCmdSuccessResponse

case class ProofCreated(proof: String) extends WalletCmdSuccessResponse

case class ProofVerifResult(result: Boolean) extends WalletCmdSuccessResponse

case class PackedMsg(msg: Array[Byte], metadata: Option[PayloadMetadata] = None)
  extends WalletCmdSuccessResponse

case class UnpackedMsg(msg: Array[Byte],
                       senderVerKey: Option[VerKeyStr],
                       recipVerKey: Option[VerKeyStr]) extends WalletCmdSuccessResponse {
  def msgString: String = new String(msg)
}

object UnpackedMsg {
  def apply(msg: String, senderVerKey: Option[VerKeyStr] = None, recipVerKey: Option[VerKeyStr] = None): UnpackedMsg =
    UnpackedMsg(msg.getBytes, senderVerKey, recipVerKey)
}

