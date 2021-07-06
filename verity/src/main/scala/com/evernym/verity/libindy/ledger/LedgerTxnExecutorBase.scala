package com.evernym.verity.libindy.ledger

import akka.actor.ActorSystem
import com.evernym.verity.Exceptions.{InvalidValueException, MissingReqFieldException, NoResponseFromLedgerPoolServiceException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status
import com.evernym.verity.Status.{TIMEOUT, UNHANDLED, _}
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.appStateManager.{AppStateUpdateAPI, ErrorEvent, MildSystemError, RecoverIfNeeded, SeriousSystemError}
import com.evernym.verity.actor.wallet.SignLedgerRequest
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.config.ConfigUtil.findTAAConfig
import com.evernym.verity.config.{AppConfig, CommonConfig, ConfigUtil}
import com.evernym.verity.ledger._
import com.evernym.verity.libindy.ledger.LedgerTxnExecutorBase._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.util.LogUtil.logFutureDuration
import com.evernym.verity.util.OptionUtil.orNone
import com.evernym.verity.util.Util.getJsonStringFromMap
import com.evernym.verity.util.{TAAUtil, Util}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.IndyException
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.ledger.Ledger._
import org.hyperledger.indy.sdk.pool.{LedgerNotFoundException, Pool}

import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.{Future, Promise, TimeoutException}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

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
}

trait SubmitToLedger {
  def submitRequest(pool: Pool, request: String): Future[RawLedgerResponse]
}
object SubmitToLedger extends SubmitToLedger {
  def submitRequest(pool: Pool, request: String): Future[RawLedgerResponse] = toFuture(Ledger.submitRequest(pool, request))
}

trait LedgerTxnExecutorBase extends LedgerTxnExecutor {

  implicit def actorSystem: ActorSystem
  def appConfig: AppConfig
  def walletAPI: Option[WalletAPI]
  def pool: Option[Pool]
  def ledgerSubmitAPI: SubmitToLedger = SubmitToLedger

  val logger: Logger = getLoggerByClass(classOf[LedgerTxnExecutorV1])

  def getResultFromJson(jsonResp: String): LedgerResult = Util.getMapWithAnyValueFromJsonString(jsonResp)

  def currentTAA: Option[TransactionAuthorAgreement]

  def retryRecovery(submitter: Submitter,
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
                s"the digest, mechanism, and time-of-acceptance to ${CommonConfig.LIB_INDY_LEDGER_TAA_AGREEMENTS} " +
                s"for TAA version $currentVersion AND enable ${CommonConfig.LIB_INDY_LEDGER_TAA}."
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

  def appendTAAToRequest(ledgerRequest: LedgerRequest, taa: Option[TransactionAuthorAgreement]): Future[LedgerRequest] = {
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


  def prepareRequest(submitterDetail: Submitter,
                     request:         LedgerRequest): Future[LedgerRequest] = {
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

  def submitRequest(request: LedgerRequest, pool: Pool): Future[RawLedgerResponse] = {
    logFutureDuration(logger, "ledger request sent") {
      ledgerSubmitAPI.submitRequest(
        pool,
        request.req
      )
    }
  }

  def parseResponse(response: RawLedgerResponse): LedgerResult = {
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

  def handleError: Throwable ?=> LedgerResult = {
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
                      request:         LedgerRequest): Future[LedgerResult] = {
      if(pool.isEmpty) {
        Future.failed(StatusDetailException(LEDGER_NOT_CONNECTED))
      } else {
        prepareRequest(submitterDetail, request)
          .flatMap(submitRequest(_, pool.get))
          .map(parseResponse)
          .recoverWith(retryRecovery(submitterDetail, LedgerRequest(request.req, request.needsSigning)))
          .recover(handleError)
      }
  }

  def getOptFieldFromResult(resp: LedgerResult, fieldToExtract: String): Option[Any] = {
    val result = extractReqValue(resp, RESULT).asInstanceOf[LedgerResult]
    extractOptValue(result, fieldToExtract)
  }

  private def submitGetRequest(submitter: Submitter,
                               req: String,
                               needsSigning: Boolean): Future[LedgerResult] = {
    completeRequest(submitter, LedgerRequest(req, needsSigning)).recover {
      case e: StatusDetailException => throw e
    }
  }

  private def submitWriteRequest(submitterDetail: Submitter,
                                 reqDetail: LedgerRequest):Future[TxnResp] ={
    completeRequest(submitterDetail, reqDetail).map { resp =>
      AppStateUpdateAPI(actorSystem).publishEvent(RecoverIfNeeded(CONTEXT_LEDGER_OPERATION))
      buildTxnRespForWriteOp(resp)
    }.recover {
      case e: StatusDetailException => throw e
    }
  }

  override def getAttrib(submitter: Submitter,
                         did: DID,
                         attrName: String): Future[GetAttribResp] = {
    toFuture(buildGetAttribRequest(submitter.did, did, attrName, null, null)) flatMap { req =>
      submitGetRequest(submitter, req, needsSigning=true).map{ r =>
        val txnResp = buildTxnRespForReadOp(r)
        val data = getOptFieldFromResult(r, DATA).map(_.toString)
        GetAttribResp(txnResp)
      }
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
              version.zip(text).headOption
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

  override def getNym(submitterDetail: Submitter, did: DID): Future[GetNymResp] = {
    toFuture(buildGetNymRequest(submitterDetail.did, did)) flatMap { req =>
      submitGetRequest(submitterDetail, req, needsSigning=false).map{ r =>
        val txnResp = buildTxnRespForReadOp(r)
        txnResp.data.map{ d =>
          val nym = d.get("dest").flatMap(orNone[String])
          val verkey = d.get("verkey").flatMap(orNone[String])
          val role = d.get("role").flatMap(orNone[String])

          nym.zip(verkey).headOption match {
            case Some(x) => GetNymResp(txnResp, Some(x._1), Some(x._2), role)
            case None => throw StatusDetailException(LEDGER_DATA_INVALID)
          }
        }.getOrElse(GetNymResp(txnResp, None, None)) // Ledger did not have nym
      }
    }
  }

  override def writeSchema(submitterDID: DID,
                           schemaJson: String,
                           walletAccess: WalletAccess): Future[TxnResp] = {
    toFuture(buildSchemaRequest(submitterDID, schemaJson)) flatMap { req =>
      val schemaReq = LedgerRequest(req, needsSigning=false, taa=None)
      appendTAAToRequest(schemaReq, currentTAA) flatMap { reqWithOptTAA =>
        val promise = Promise[TxnResp]()
        walletAccess.signRequest(submitterDID, reqWithOptTAA.req) {
          case Success(signedRequest) =>
            promise.completeWith(
              submitWriteRequest(Submitter(submitterDID, None), reqWithOptTAA.prepared(signedRequest.req))
            )
          case Failure(ex) => promise.failure(ex)
        }
        promise.future
      }
    }
  }

  override def prepareSchemaForEndorsement(submitterDID: DID,
                                           schemaJson: String,
                                           endorserDID: DID,
                                           walletAccess: WalletAccess): Future[LedgerRequest] = {
    toFuture(buildSchemaRequest(submitterDID, schemaJson)) flatMap { req =>
      val schemaReq = LedgerRequest(req, needsSigning=false, taa=None)
      appendTAAToRequest(schemaReq, currentTAA) flatMap { reqWithOptTAA =>
        toFuture(appendRequestEndorser(reqWithOptTAA.req, endorserDID)) flatMap { reqWithEndorser =>
          val promise = Promise[LedgerRequest]()
          walletAccess.multiSignRequest(submitterDID, reqWithEndorser)(promise.complete)
          promise.future
        }
      }
    }
  }

  def writeCredDef(submitterDID: DID,
                   credDefJson: String,
                   walletAccess: WalletAccess): Future[TxnResp] = {
    toFuture(buildCredDefRequest(submitterDID, credDefJson)) flatMap { req =>
      val credDefReq = LedgerRequest(req, needsSigning=false, taa=None)
      appendTAAToRequest(credDefReq, currentTAA) flatMap { reqWithOptTAA =>
        val promise = Promise[TxnResp]()

        walletAccess.signRequest(submitterDID, reqWithOptTAA.req) {
          case Success(signedRequest) => promise.completeWith(
            submitWriteRequest(Submitter(submitterDID, None), reqWithOptTAA.prepared(signedRequest.req))
          )
          case Failure(ex) => promise.failure(ex)
        }
        promise.future
      }
    }
  }

  override def prepareCredDefForEndorsement(submitterDID: DID,
                                            credDefJson: String,
                                            endorserDID: DID,
                                            walletAccess: WalletAccess): Future[LedgerRequest] = {
    toFuture(buildCredDefRequest(submitterDID, credDefJson)) flatMap { req =>
      val credDefReq = LedgerRequest(req, needsSigning=false, taa=None)
      appendTAAToRequest(credDefReq, currentTAA) flatMap { reqWithOptTAA =>
        toFuture(appendRequestEndorser(reqWithOptTAA.req, endorserDID)) flatMap{ reqWithEndorser =>
          val promise = Promise[LedgerRequest]()
          walletAccess.multiSignRequest(submitterDID, reqWithEndorser)(promise.complete)
          promise.future
        }
      }
    }
  }

  override def getSchema(submitterDetail: Submitter, schemaId: String): Future[GetSchemaResp] = {
    toFuture(buildGetSchemaRequest(submitterDetail.did, schemaId)) flatMap { req =>
      submitGetRequest(submitterDetail, req, needsSigning=false).flatMap{ value =>
        toFuture(Ledger.parseGetSchemaResponse(getJsonStringFromMap(value))).map( x =>
          (
            value,
            Option(DefaultMsgCodec.fromJson[SchemaV1](x.getObjectJson))
          )
        ).recover {
          case _: LedgerNotFoundException => (value, None)
          case NonFatal(_) => throw StatusDetailException(LEDGER_DATA_INVALID)
        }
      }.map { r =>
        GetSchemaResp(buildTxnRespForReadOp(r._1), r._2)
      }
    }
  }

  override def getCredDef(submitterDetail: Submitter, credDefId: String): Future[GetCredDefResp] = {
    toFuture(buildGetCredDefRequest(submitterDetail.did, credDefId)) flatMap { req =>
      submitGetRequest(submitterDetail, req, needsSigning=false).flatMap { value =>
        toFuture(Ledger.parseGetCredDefResponse(getJsonStringFromMap(value))).map( x =>
          (
            value,
            Option(DefaultMsgCodec.fromJson[CredDefV1](x.getObjectJson))
          )
        ).recover {
          case _: LedgerNotFoundException => (value, None)
          case NonFatal(_) => throw StatusDetailException(LEDGER_DATA_INVALID)
        }
      }.map { r =>
        GetCredDefResp(buildTxnRespForReadOp(r._1), r._2)
      }
    }
  }

  override def addNym(submitterDetail: Submitter, targetDid: DidPair): Future[TxnResp] = {
    toFuture(buildNymRequest(
      submitterDetail.did,
      targetDid.DID,
      targetDid.verKey,
      null,
      null
    )) flatMap { req =>
      submitWriteRequest(submitterDetail, LedgerRequest(req, taa = currentTAA))
    }
  }

  override def addAttrib(submitterDetail: Submitter,
                         did: DID,
                         attrName: String,
                         attrValue: String): Future[TxnResp] = {
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

  def extractReqValue(resp: LedgerResult, fieldToExtract: String): Any = {
    resp.get(fieldToExtract) match {
      case Some(r) if r == null =>
        throw new InvalidValueException(Option("ledger response parsing error " +
          s"(invalid value found for field '$fieldToExtract': $r)"))
      case Some(r) => r
      case _ =>
        throw new MissingReqFieldException(Option(s"ledger response parsing error ('$fieldToExtract' key is missing)"))
    }
  }

  def extractOptValue(resp: LedgerResult, fieldToExtract: String, nullAllowed: Boolean = false): Option[Any] = {
    try {
      Option(extractReqValue(resp, fieldToExtract))
    } catch {
      case _:InvalidValueException if nullAllowed => None
      case _:MissingReqFieldException => None
    }
  }

}
