package com.evernym.verity.protocol.container.asyncapis.ledger

import akka.actor.ActorRef
import com.evernym.verity.Exceptions.NotFoundErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.cache.base.{Cache, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.cache.fetchers.{GetCredDef, GetSchema}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.ledger._
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.asyncService.AsyncOpRunner
import com.evernym.verity.protocol.engine.asyncService.ledger.{LedgerAccess, LedgerAccessException}
import com.evernym.verity.protocol.engine.asyncService.wallet.WalletAccess
import com.evernym.verity.protocol.engine.{BaseAsyncAccessImpl, DID}

import scala.concurrent.Future
import scala.util.Try


class LedgerAccessAPI(cache: Cache,
                      ledgerSvc: LedgerSvc,
                      _walletAccess: WalletAccess)
                     (implicit asyncAPIContext: AsyncAPIContext)
  extends LedgerAccess with BaseAsyncAccessImpl {

  override def walletAccess: WalletAccess =  _walletAccess
  override def asyncOpRunner: AsyncOpRunner = asyncAPIContext.asyncOpRunner
  implicit val senderActorRef: ActorRef = asyncAPIContext.senderActorRef

  private def getSchemaBase(schemaIds: Set[String]): Future[Map[String, GetSchemaResp]] = {
    val keyDetails = schemaIds.map { sId =>
      KeyDetail(GetSchema(sId), required = true)
    }
    val gcop = GetCachedObjectParam(keyDetails, LEDGER_GET_SCHEMA_FETCHER_ID)
    cache.getByParamAsync(gcop).map { cqr =>
      val result = schemaIds.map { sId => sId -> cqr.getReq[GetSchemaResp](sId) }.toMap
      if (result.keySet == schemaIds) result
      else throw new NotFoundErrorException(s"schemas not found for ids: ${schemaIds.diff(result.keySet)}")
    }.recover {
      case e: Throwable => throw LedgerAccessException(e.getMessage)
    }
  }

  override def getSchema(schemaId: String)
                         (handler: Try[GetSchemaResp] => Unit): Unit = {
    withAsyncOpRunner(
      {
        getSchemaBase(Set(schemaId)).map { r => r(schemaId) }
      },
      handler
    )
  }

  override def getSchemas(schemaIds: Set[String])
                         (handler: Try[Map[String, GetSchemaResp]] => Unit): Unit = {
    withAsyncOpRunner({ getSchemaBase(schemaIds)}, handler)
  }

  private def getCredDefsBase(credDefIds: Set[String]): Future[Map[String, GetCredDefResp]] = {
    val keyDetails = credDefIds.map { cId =>
      KeyDetail(GetCredDef(cId), required = true)
    }
    val gcop = GetCachedObjectParam(keyDetails, LEDGER_GET_CRED_DEF_FETCHER_ID)
    cache.getByParamAsync(gcop).map { cqr =>
      val result = credDefIds.map { cId => cId -> cqr.getReq[GetCredDefResp](cId) }.toMap
      if (result.keySet == credDefIds) result
      else throw new NotFoundErrorException(s"cred defs not found for ids: ${credDefIds.diff(result.keySet)}")
    }.recover {
      case e: Throwable => throw LedgerAccessException(e.getMessage)
    }
  }

  override def getCredDef(credDefId: String)
                         (handler: Try[GetCredDefResp] => Unit): Unit = {
    withAsyncOpRunner(
      {
        getCredDefsBase(Set(credDefId)).map { r => r(credDefId) }
      },
      handler
    )
  }

  override def getCredDefs(credDefIds: Set[String])
                          (handler: Try[Map[String, GetCredDefResp]] => Unit): Unit = {
    withAsyncOpRunner({getCredDefsBase(credDefIds)}, handler)
  }

  override def writeSchema(submitterDID: String, schemaJson: String)
                          (handler: Try[Either[StatusDetail, TxnResp]] => Unit): Unit = {
    withAsyncOpRunner(
      {ledgerSvc.writeSchema(submitterDID, schemaJson, walletAccess)},
      handler
    )
  }

  override def prepareSchemaForEndorsement(submitterDID: DID, schemaJson: String, endorserDID: DID)
                                          (handler: Try[LedgerRequest] => Unit): Unit = {
    withAsyncOpRunner(
      {ledgerSvc.prepareSchemaForEndorsement(submitterDID, schemaJson, endorserDID, walletAccess)},
      handler
    )
  }

  override def writeCredDef(submitterDID: DID,
                            credDefJson: String)
                           (handler: Try[Either[StatusDetail, TxnResp]] => Unit): Unit = {
    withAsyncOpRunner(
      {ledgerSvc.writeCredDef(submitterDID, credDefJson, walletAccess)},
      handler)
  }

  override def prepareCredDefForEndorsement(submitterDID: DID, credDefJson: String, endorserDID: DID)
                                           (handler: Try[LedgerRequest] => Unit): Unit = {
    withAsyncOpRunner(
      {ledgerSvc.prepareCredDefForEndorsement(submitterDID, credDefJson, endorserDID, walletAccess)},
      handler
    )
  }
}

object LedgerAccessAPI {
  def apply(cache: Cache, ledgerSvc: LedgerSvc, walletAccess: WalletAccess)(implicit asyncAPIContext: AsyncAPIContext) =
    new LedgerAccessAPI(cache, ledgerSvc, walletAccess)
}
