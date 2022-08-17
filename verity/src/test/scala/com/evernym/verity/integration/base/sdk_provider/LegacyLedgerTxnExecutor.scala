package com.evernym.verity.integration.base.sdk_provider

import com.evernym.vdrtools.ledger.Ledger
import com.evernym.vdrtools.ledger.Ledger.{buildGetCredDefRequest, buildGetSchemaRequest}
import com.evernym.vdrtools.pool.{LedgerNotFoundException, Pool}
import com.evernym.verity.actor.wallet.SignLedgerRequest
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.ledger.{CredDefV1, LedgerRequest, SchemaV1, Submitter, TransactionAuthorAgreement}
import com.evernym.verity.util.Util
import com.evernym.verity.util.Util.getJsonStringFromMap
import com.evernym.verity.util2.Status.{LEDGER_DATA_INVALID, LEDGER_NOT_CONNECTED, StatusDetailException}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vdr.{CredDef, Schema}
import com.evernym.verity.vdrtools.ledger.LedgerTxnExecutorBase._
import com.evernym.verity.vdrtools.ledger.{InvalidClientTaaAcceptanceError, LedgerTxnUtil}

import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal


class LegacyLedgerTxnExecutor(pool: Option[Pool],
                              val walletAPI: Option[WalletAPI])
                             (implicit ec: ExecutionContext) {

  type RawLedgerResponse = String

  private val readSubmitter = Submitter()

  def getSchema(schemaId: String): Future[Schema] = {
    toFuture(buildGetSchemaRequest(readSubmitter.did, schemaId)) flatMap { req =>
      submitGetRequest(readSubmitter, req, needsSigning=false).flatMap{ value =>
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
        r._2 match {
          case Some(schemaV1) => Schema(schemaId, JsonMsgUtil.createJsonString(schemaV1))
          case None => throw new RuntimeException(s"schema not found with id: $schemaId")
        }
      }
    }
  }

  def getCredDef(credDefId: String): Future[CredDef] = {
    toFuture(buildGetCredDefRequest(readSubmitter.did, credDefId)) flatMap { req =>
      submitGetRequest(readSubmitter, req, needsSigning = false).flatMap { value =>
        toFuture(Ledger.parseGetCredDefResponse(getJsonStringFromMap(value))).map(x =>
          (
            value,
            Option(DefaultMsgCodec.fromJson[CredDefV1](x.getObjectJson))
          )
        ).recover {
          case _: LedgerNotFoundException => (value, None)
          case NonFatal(_) => throw StatusDetailException(LEDGER_DATA_INVALID)
        }
      }.map { r =>
        r._2 match {
          case Some(credDefV1) => CredDef(credDefId, "", JsonMsgUtil.createJsonString(credDefV1))
          case None => throw new RuntimeException(s"cred def not found with id: $credDefId")
        }
      }
    }
  }


  private def submitGetRequest(submitter: Submitter,
                               req: String,
                               needsSigning: Boolean): Future[LedgerResult] = {
    completeRequest(submitter, LedgerRequest(req, needsSigning)).recover {
      case e: StatusDetailException => throw e
    }
  }

  private def completeRequest(submitterDetail: Submitter,
                      request: LedgerRequest): Future[LedgerResult] = {
    if (pool.isEmpty) {
      Future.failed(StatusDetailException(LEDGER_NOT_CONNECTED))
    } else {
      prepareRequest(submitterDetail, request)
        .flatMap(submitRequest(_, pool.get))
        .map { resp =>
          parseResponse(resp)
        }
    }
  }

  private def submitRequest(request: LedgerRequest, pool: Pool): Future[RawLedgerResponse] = {
    toFuture(Ledger.submitRequest(pool, request.req))
  }

  private def parseResponse(response: RawLedgerResponse): LedgerResult = {
    val pr = Util.getMapWithAnyValueFromJsonString(response)
    pr.get(OP) match {
      case Some(op) if op.equals(REPLY) => pr
      case Some(op) if op.equals(REJECT) || op.equals(REQNACK) =>
        val err = pr.get(REASON)
          .flatMap {
            case reason: String if reason.contains("InvalidClientTaaAcceptanceError") =>
              Some(new InvalidClientTaaAcceptanceError(reason))
            case _ => None
          }
        err match {
          case Some(e) => throw e
          case None => pr
        }
      case _ => pr
    }
  }

  private def prepareRequest(submitterDetail: Submitter,
                             request: LedgerRequest): Future[LedgerRequest] = {
    Future.successful(request)
      .flatMap { r => appendTAAToRequest(r, r.taa) }
      .flatMap { r =>
        if (r.needsSigning)
          walletAPI
            .getOrElse(throw new Exception("WalletAPI required for signing ledger transactions"))
            .executeAsync[LedgerRequest](SignLedgerRequest(r, submitterDetail))(submitterDetail.wapReq)
        else Future.successful(r)
      }
  }

  private def appendTAAToRequest(ledgerRequest: LedgerRequest,
                                 taa: Option[TransactionAuthorAgreement]): Future[LedgerRequest] = {
    LedgerTxnUtil.appendTAAToRequest(ledgerRequest, taa)
  }

}
