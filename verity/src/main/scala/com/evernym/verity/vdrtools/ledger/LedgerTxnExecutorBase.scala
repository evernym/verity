package com.evernym.verity.vdrtools.ledger

import akka.actor.ActorSystem
import com.evernym.verity.util2.Exceptions.{InvalidValueException, NoResponseFromLedgerPoolServiceException}
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.util2.Status.{TIMEOUT, UNHANDLED, _}
import com.evernym.verity.actor.appStateManager.{AppStateUpdateAPI, ErrorEvent, MildSystemError, RecoverIfNeeded, SeriousSystemError}
import com.evernym.verity.actor.wallet.SignLedgerRequest
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.config.ConfigUtil.findTAAConfig
import com.evernym.verity.config.{AppConfig, ConfigConstants, ConfigUtil}
import com.evernym.verity.ledger._
import com.evernym.verity.vdrtools.ledger.LedgerTxnExecutorBase._
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.did.{DidPair, DidStr}
import com.evernym.verity.protocol.engine.asyncapi.wallet.LedgerRequestResult
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.util.LogUtil.logFutureDuration
import com.evernym.verity.util.Util.getJsonStringFromMap
import com.evernym.verity.util.{TAAUtil, Util}
import com.evernym.verity.util2.Status
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.typesafe.scalalogging.Logger
import com.evernym.vdrtools.IndyException
import com.evernym.vdrtools.ledger.Ledger
import com.evernym.vdrtools.ledger.Ledger._
import com.evernym.vdrtools.pool.Pool
import com.evernym.verity.actor.agent.{AttrName, AttrValue}
import com.evernym.verity.vdrtools.ledger.LedgerTxnUtil.appendTAAToRequest

import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.{ExecutionContext, Future, TimeoutException}

class InvalidClientTaaAcceptanceError(msg: String) extends Exception(msg)
class UnknownTxnRejectReasonError(msg: String) extends Exception(msg)
class TaaConfiguredVersionInvalidError(msg: String) extends Exception(msg)
class TaaConfigurationForVersionNotFoundError(msg: String) extends Exception(msg)
class TaaFailedToGetCurrentVersionError(msg: String) extends Exception(msg)
class TaaRequiredButDisabledError(msg: String) extends Exception(msg)
class TaaNotRequiredButIncludedError(msg: String) extends Exception(msg)
class UnhandledIndySdkException(msg: String) extends Exception(msg)

object LedgerTxnExecutorBase {

  val RESULT = "result"
  val REJECT = "REJECT"
  val REPLY = "REPLY"
  val REQNACK = "REQNACK"
  val TXN_TIME = "txnTime"
  val DEST = "dest"
  val IDENTIFIER = "identifier"
  val REQ_ID = "reqId"
  val SEQ_NO = "seqNo"
  val TYPE = "type"
  val DATA = "data"
  val METADATA = "metadata"
  val TXN = "txn"
  val FROM = "from"
  val TXN_METADATA ="txnMetadata"
  val REASON = "reason"
  val OP = "op"


  type LedgerResult = Map[String, Any]
  type RawLedgerResponse = String

  def toLedgerRequest(result: LedgerRequestResult): LedgerRequest = {
    LedgerRequest(
      result.req,
      result.needsSigning,
      result.taa.map(t => TransactionAuthorAgreement(
        t.version,
        t.digest,
        t.mechanism,
        t.timeOfAcceptance
      ))
    )
  }
}

trait SubmitToLedger {
  def submitRequest(pool: Pool, request: String): Future[RawLedgerResponse]
}
object SubmitToLedger extends SubmitToLedger {
  def submitRequest(pool: Pool, request: String): Future[RawLedgerResponse] = toFuture(Ledger.submitRequest(pool, request))
}

trait LedgerTxnExecutorBase extends LedgerTxnExecutor with HasExecutionContextProvider {
  private implicit val executionContext: ExecutionContext = futureExecutionContext

  implicit def actorSystem: ActorSystem
  def appConfig: AppConfig
  def walletAPI: Option[WalletAPI]
  def pool: Option[Pool]
  def ledgerSubmitAPI: SubmitToLedger = SubmitToLedger

  val logger: Logger = getLoggerByClass(classOf[LedgerTxnExecutorV1])

  private def getResultFromJson(jsonResp: String): LedgerResult = Util.getMapWithAnyValueFromJsonString(jsonResp)

  def currentTAA: Option[TransactionAuthorAgreement]

  private def retryRecovery(submitter: Submitter,
                            reqWithoutTaa: LedgerRequest): PartialFunction[Throwable, Future[LedgerResult]] = {
    case taaFailure: InvalidClientTaaAcceptanceError =>
      if(ConfigUtil.isTAAConfigEnabled(appConfig)) {
        getTAA(submitter).flatMap { taaResp =>
          val currentVersion = reqWithoutTaa.taa.map(_.version).getOrElse("-1")
          val newVersion = taaResp.taa.version
          val newTaa = findTAAConfig(appConfig, newVersion)

          newTaa match {
            case Some(t) if t.version != currentVersion =>
              logger.warn(s"Current TAA has expired -- New TAA is available with restart")
              logger.debug(s"Retrying write to ledger with active TAA: $newTaa")
              completeRequest(submitter, reqWithoutTaa.copy(taa = newTaa))
            case Some(_) =>
              val errorMsg = s"Invalid TAA -- Current configured TAA for version $currentVersion is not valid."
              throw new TaaConfiguredVersionInvalidError(errorMsg + " Error details: " + taaFailure.getMessage)
            case None =>
              val errorMsg = s"No Valid TAA -- TAA version $currentVersion is not configured. You must add " +
                s"the digest, mechanism, and time-of-acceptance to ${ConfigConstants.LIB_INDY_LEDGER_TAA_AGREEMENTS} " +
                s"for TAA version $currentVersion AND enable ${ConfigConstants.LIB_INDY_LEDGER_TAA}."
              throw new TaaConfigurationForVersionNotFoundError(errorMsg + " Error details: " + taaFailure.getMessage)
          }
        }.recover {
          case StatusDetailException(_) => throw new TaaFailedToGetCurrentVersionError(taaFailure.getMessage)
        }
      } else if (taaFailure.getMessage.contains("Txn Author Agreement acceptance has not been set yet and not allowed in requests")) {
        Future.failed(StatusDetailException(Status.TAA_NOT_REQUIRED_BUT_INCLUDED))
      } else {
        throw new TaaRequiredButDisabledError(taaFailure.getMessage)
      }
    case sdkFailure: IndyException =>
      // TODO: Use sdkFailure.getSdkMessage once https://jira.hyperledger.org/browse/IS-1444 is fixed.
      logger.error(s"Indy SDK exception: ${sdkFailure.getMessage}")
      throw new UnhandledIndySdkException(sdkFailure.getMessage)
    case e => Future.failed(e)
  }

  private def prepareRequest(submitterDetail: Submitter,
                             request: LedgerRequest): Future[LedgerRequest] = {
    Future.successful(request)
      .flatMap { r => appendTAAToRequest(r, r.taa) }
      .flatMap{ r =>
        if(r.needsSigning)
          walletAPI
            .getOrElse(throw new Exception("WalletAPI required for signing ledger transactions"))
            .executeAsync[LedgerRequest](SignLedgerRequest(r, submitterDetail))(submitterDetail.wapReq)
        else Future.successful(r)
      }
  }

  private def submitRequest(request: LedgerRequest, pool: Pool): Future[RawLedgerResponse] = {
    logFutureDuration(logger, "ledger request sent") {
      ledgerSubmitAPI.submitRequest(
        pool,
        request.req
      )
    }
  }

  private def parseResponse(response: RawLedgerResponse): LedgerResult = {
    val pr = getResultFromJson(response)
    pr.get(OP) match {
      case Some(op) if op.equals(REPLY) =>
        AppStateUpdateAPI(actorSystem).publishEvent(RecoverIfNeeded(CONTEXT_LEDGER_OPERATION))
        pr
      case Some(op) if op.equals(REJECT) || op.equals(REQNACK) =>
        // TODO: get REASON_CODE/STATUS_CODE (whatever indy folks end up calling it) instead of REASON and change
        //       if conditions in each flatMap case to use the code instead of substring matching.
        //       See https://jira.hyperledger.org/browse/IS-1437 for details
        //
        // NOTE: Do not remove 'r => r match {...}' from around the following case statements. flatMap usually does not
        //       need to bind to 'r' and then pattern match. Simply passing the PartialFunction containing the case
        //       statements should be sufficient. However, for some unknown reason, when the `r match` block is excluded
        //       'case reason: String =>` binds 'reason' to the LedgerTxnExecutor instance instead of of the 'reason'
        //       String.
        val err = pr.get(REASON)
          .flatMap {
            case reason: String if reason.contains("InvalidClientTaaAcceptanceError") =>
              logger.warn(s"Ledger transaction rejected. Reason: $reason")
              Some(new InvalidClientTaaAcceptanceError(reason))
            case _ => None
          }
        err match {
          case Some(e) => throw e
          case None    => pr
        }
      case _ => pr
    }
  }

  private def handleError: Throwable ?=> LedgerResult = {
    case e: StatusDetailException => throw e
    case e: TimeoutException =>
      val errorMsg = "no response from ledger pool: " + e.toString
      AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(MildSystemError, CONTEXT_LEDGER_OPERATION,
        new NoResponseFromLedgerPoolServiceException(Option(errorMsg)), Option(errorMsg)))
      throw StatusDetailException(TIMEOUT.withMessage(e.getMessage))
    case e: TaaConfiguredVersionInvalidError =>
      AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_LEDGER_OPERATION, e,
        Some(s"$TAA_CONFIGURED_VERSION_INVALID Details: ${e.getMessage}")))
      throw StatusDetailException(TAA_CONFIGURED_VERSION_INVALID)
    case e: TaaConfigurationForVersionNotFoundError =>
      AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_LEDGER_OPERATION, e,
        Some(s"$TAA_CONFIGURATION_FOR_VERSION_NOT_FOUND Details: ${e.getMessage}")))
      throw StatusDetailException(TAA_CONFIGURATION_FOR_VERSION_NOT_FOUND)
    case e: TaaFailedToGetCurrentVersionError =>
      AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_LEDGER_OPERATION, e,
        Some(s"$TAA_FAILED_TO_GET_CURRENT_VERSION Details: ${e.getMessage}")))
      throw StatusDetailException(TAA_FAILED_TO_GET_CURRENT_VERSION)
    case e: TaaRequiredButDisabledError =>
      AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_LEDGER_OPERATION, e,
        Some(s"$TAA_REQUIRED_BUT_DISABLED Details: ${e.getMessage}")))
      throw StatusDetailException(TAA_REQUIRED_BUT_DISABLED)
    case e: UnknownTxnRejectReasonError =>
      AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_LEDGER_OPERATION, e,
        Some(s"$LEDGER_UNKNOWN_REJECT_ERROR Details: ${e.getMessage}")))
      throw StatusDetailException(LEDGER_UNKNOWN_REJECT_ERROR )
    case e: UnhandledIndySdkException =>
      AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_LEDGER_OPERATION, e,
        Some(s"$INDY_SDK_UNHANDLED_EXCEPTION Details: ${e.getMessage}")))
      throw StatusDetailException(INDY_SDK_UNHANDLED_EXCEPTION)
    case e: Exception =>
      val errorMsg = "unhandled error/response while interacting with ledger: " + e.toString
      AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(MildSystemError, CONTEXT_LEDGER_OPERATION, e, Option(errorMsg)))
      throw StatusDetailException(UNHANDLED.withMessage( e.getMessage))
    case e =>
      logger.warn(e.getMessage)
      throw StatusDetailException(LEDGER_UNKNOWN_ERROR)
  }

  def completeRequest(submitterDetail: Submitter,
                      request: LedgerRequest): Future[LedgerResult] = {
      if(pool.isEmpty) {
        Future.failed(StatusDetailException(LEDGER_NOT_CONNECTED))
      } else {
        prepareRequest(submitterDetail, request)
          .flatMap(submitRequest(_, pool.get))
          .map { resp =>
            AppStateUpdateAPI(actorSystem).publishEvent(RecoverIfNeeded(CONTEXT_LEDGER_OPERATION))
            parseResponse(resp)
          }
          .recoverWith(retryRecovery(submitterDetail, LedgerRequest(request.req, request.needsSigning)))
          .recover(handleError)
      }
  }

  private def submitGetRequest(submitter: Submitter,
                               req: String,
                               needsSigning: Boolean): Future[LedgerResult] = {
    completeRequest(submitter, LedgerRequest(req, needsSigning)).recover {
      case e: StatusDetailException => throw e
    }
  }

  private def submitWriteRequest(submitterDetail: Submitter,
                                 reqDetail: LedgerRequest):Future[Unit] ={
    completeRequest(submitterDetail, reqDetail).map { resp =>
      buildTxnRespForWriteOp(resp)
    }.recover {
      case e: StatusDetailException => throw e
    }
  }

  override def getTAA(submitter: Submitter): Future[GetTAAResp] = {
    toFuture(buildGetTxnAuthorAgreementRequest(submitter.did, null)) flatMap { req =>
      submitGetRequest(submitter, req, needsSigning=false).map{ r =>
        try {
          val txnResp = buildTxnRespForReadOp(r)

          val data = txnResp.data.flatMap{ m =>
            try {
              val version = m.get("version").map(_.asInstanceOf[String])
              val text = m.get("text").map(_.asInstanceOf[String])
              version.zip(text)
            } catch {
              case _: ClassCastException => None
            }
          }.map(x => LedgerTAA(x._1, x._2))

          data match {
            case Some(d) => GetTAAResp(txnResp, d)
            case None =>
              if(txnResp.data.isEmpty) throw StatusDetailException(TAA_NOT_SET_ON_THE_LEDGER)
              else throw StatusDetailException(TAA_INVALID_JSON)
          }
        } catch {
          case _:InvalidValueException => throw StatusDetailException(TAA_NOT_SET_ON_THE_LEDGER)
        }
      }
    }
  }

  override def addNym(submitterDetail: Submitter, targetDid: DidPair): Future[Unit] = {
    toFuture(buildNymRequest(
      submitterDetail.did,
      targetDid.did,
      targetDid.verKey,
      null,
      null
    )) flatMap { req =>
      submitWriteRequest(submitterDetail, LedgerRequest(req, taa = currentTAA))
    }
  }

  override def addAttrib(submitterDetail: Submitter,
                         did: DidStr,
                         attrName: AttrName,
                         attrValue: AttrValue): Future[Unit] = {
    val raw = getJsonStringFromMap (Map(attrName -> attrValue))
    toFuture(buildAttribRequest(
      submitterDetail.did,
      did,
      null,
      raw,
      null
    )) flatMap { req =>
      submitWriteRequest(submitterDetail, LedgerRequest(req, taa = currentTAA))
    }
  }

  override def getAttrib(submitter: Submitter,
                         did: DidStr,
                         attrName: AttrName): Future[GetAttribResp] = {
    toFuture(buildGetAttribRequest(submitter.did, did, attrName, null, null)) flatMap { req =>
      submitGetRequest(submitter, req, needsSigning=true).map{ r =>
        val txnResp = buildTxnRespForReadOp(r)
        getOptFieldFromResult(r, DATA).map(_.toString)
        GetAttribResp(txnResp)
      }
    }
  }

  private def getOptFieldFromResult(resp: LedgerResult, fieldToExtract: String): Option[Any] = {
    val result = extractReqValue(resp, RESULT).asInstanceOf[LedgerResult]
    extractOptValue(result, fieldToExtract)
  }

}

object LedgerTxnUtil {
  def appendTAAToRequest(ledgerRequest: LedgerRequest, taa: Option[TransactionAuthorAgreement])
                        (implicit ec: ExecutionContext): Future[LedgerRequest] = {
    // IMPORTANT - Either use (text and version) OR (digest). Sending text or version with digest will result
    //             in an IndyException - Error: Invalid structure Caused by: Invalid combination of params:
    //             `text` and `version` should be passed or skipped together.
    taa match {
      case Some(taa) =>
        toFuture(appendTxnAuthorAgreementAcceptanceToRequest(
          ledgerRequest.req,
          null,
          null,
          taa.digest,
          taa.mechanism,
          TAAUtil.taaAcceptanceEpochDateTime(taa.timeOfAcceptance)
        )) map { req =>
          ledgerRequest.prepared(newRequest = req)
        }
      case None => Future.successful(ledgerRequest)
    }
  }

}