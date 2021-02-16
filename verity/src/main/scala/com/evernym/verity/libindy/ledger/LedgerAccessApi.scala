package com.evernym.verity.libindy.ledger

import com.evernym.verity.Exceptions.NotFoundErrorException
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.cache.base.{Cache, CacheQueryResponse, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.cache.fetchers.{GetCredDef, GetSchema}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerRequest, LedgerSvc, TxnResp}
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.external_api_access.{LedgerAccess, LedgerAccessException, WalletAccess}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


class LedgerAccessApi(cache: Cache, ledgerSvc: LedgerSvc, _walletAccess: WalletAccess) extends LedgerAccess {

  private val maxWaitTime: FiniteDuration = 15 second

  override def walletAccess: WalletAccess =  _walletAccess

  override def getCredDef(credDefId: String): Try[GetCredDefResp] = {
    runWithInternalSpan("getCredDef","LedgerAccessApi" ) {
      val gcop = GetCachedObjectParam(KeyDetail(GetCredDef(credDefId), required = true), LEDGER_GET_CRED_DEF_FETCHER_ID)
      val result = Try(Await.result(cache.getByParamAsync(gcop), maxWaitTime))
      result match {
        case Success(cqr) if cqr.get(credDefId).isDefined => Success(cqr.getReq(credDefId))
        case Success(_) => Failure(new NotFoundErrorException(s"cred def not found with id: $credDefId"))
        case Failure(d) => Failure(LedgerAccessException(d.getMessage))
      }
    }
  }

  override def getSchema(schemaId: String): Try[GetSchemaResp] = {
    runWithInternalSpan("getSchema","LedgerAccessApi" ) {
      val gcop = GetCachedObjectParam(KeyDetail(GetSchema(schemaId), required = true), LEDGER_GET_SCHEMA_FETCHER_ID)
      val result = Try(Await.result(cache.getByParamAsync(gcop), maxWaitTime))
      result match {
        case Success(cqr) if cqr.get(schemaId).isDefined => Success(cqr.getReq(schemaId))
        case Success(_) => Failure(new NotFoundErrorException(s"schema not found with id: $schemaId"))
        case Failure(d) => Failure(LedgerAccessException(d.getMessage))
      }
    }
  }

  override def writeSchema(submitterDID: String, schemaJson: String): Try[Either[StatusDetail, TxnResp]] = {
    runWithInternalSpan("writeSchema", "LedgerAccessApi") {
      Try(Await.result(
        ledgerSvc.writeSchema(submitterDID, schemaJson, walletAccess),
        maxWaitTime
      ))
    }
  }

  override def prepareSchemaForEndorsement(submitterDID: DID, schemaJson: String, endorserDID: DID): Try[LedgerRequest] = {
    runWithInternalSpan("prepareSchemaForEndorsement", "LedgerAccessApi") {
      Try(Await.result(
        ledgerSvc.prepareSchemaForEndorsement(submitterDID, schemaJson, endorserDID, walletAccess),
        maxWaitTime
      ))
    }
  }

  override def writeCredDef(submitterDID: DID,
                            credDefJson: String): Try[Either[StatusDetail, TxnResp]] = {
    runWithInternalSpan("writeCredDef", "LedgerAccessApi") {
      Try(Await.result(
        ledgerSvc.writeCredDef(submitterDID, credDefJson, walletAccess),
        maxWaitTime
      ))
    }
  }

  override def prepareCredDefForEndorsement(submitterDID: DID, credDefJson: String, endorserDID: DID): Try[LedgerRequest] = {
    runWithInternalSpan("prepareCredDefForEndorsement", "LedgerAccessApi") {
      Try(Await.result(
        ledgerSvc.prepareCredDefForEndorsement(submitterDID, credDefJson, endorserDID, walletAccess),
        maxWaitTime
      ))
    }
  }
}

object LedgerAccessApi {
  def apply(cache: Cache, ledgerSvc: LedgerSvc, walletAccess: WalletAccess) = new LedgerAccessApi(cache, ledgerSvc, walletAccess)
}
