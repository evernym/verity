package com.evernym.verity.protocol.container.asyncapis.ledger

import com.evernym.verity.Exceptions.NotFoundErrorException
import com.evernym.verity.cache.base.{Cache, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.cache.fetchers.{GetCredDef, GetSchema}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.ledger._
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.asyncapi.ledger.{LedgerAccessException, LedgerAsyncOps}
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.{BaseAsyncOpExecutorImpl, DID}

import scala.concurrent.{ExecutionContext, Future}

class LedgerAccessAPI(cache: Cache,
                      ledgerSvc: LedgerSvc,
                      _walletAccess: WalletAccess)
                     (implicit val asyncAPIContext: AsyncAPIContext)
  extends LedgerAsyncOps
    with BaseAsyncOpExecutorImpl {

  override def walletAccess: WalletAccess = _walletAccess

  override def runGetSchema(schemaId: String): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec =>
        getSchemaBase(Set(schemaId)).map { r => r(schemaId) }
      }
    )
  }

  override def runGetSchemas(schemaIds: Set[String]): Unit = {
    withAsyncOpExecutorActor({ implicit ec => getSchemaBase(schemaIds)})
  }

  override def runGetCredDef(credDefId: String): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec =>
        getCredDefsBase(Set(credDefId)).map { r => r(credDefId) }
      }
    )
  }

  override def runGetCredDefs(credDefIds: Set[String]): Unit = {
    withAsyncOpExecutorActor({ implicit ec => getCredDefsBase(credDefIds)})
  }

  override def runWriteSchema(submitterDID: String, schemaJson: String): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec => ledgerSvc.writeSchema(submitterDID, schemaJson, walletAccess)}
    )
  }

  override def runPrepareSchemaForEndorsement(submitterDID: DID, schemaJson: String, endorserDID: DID): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec => ledgerSvc.prepareSchemaForEndorsement(submitterDID, schemaJson, endorserDID, walletAccess)}
    )
  }

  override def runWriteCredDef(submitterDID: DID, credDefJson: String): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec => ledgerSvc.writeCredDef(submitterDID, credDefJson, walletAccess)}
    )
  }

  override def runPrepareCredDefForEndorsement(submitterDID: DID, credDefJson: String, endorserDID: DID): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec => ledgerSvc.prepareCredDefForEndorsement(submitterDID, credDefJson, endorserDID, walletAccess)}
    )
  }

  private def getSchemaBase(schemaIds: Set[String])(implicit ec: ExecutionContext)
  : Future[Map[String, GetSchemaResp]] = {
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

  private def getCredDefsBase(credDefIds: Set[String])(implicit ec: ExecutionContext):
  Future[Map[String, GetCredDefResp]] = {
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
}

object LedgerAccessAPI {
  def apply(cache: Cache, ledgerSvc: LedgerSvc, walletAccess: WalletAccess)
           (implicit asyncAPIContext: AsyncAPIContext) =
    new LedgerAccessAPI(cache, ledgerSvc, walletAccess)
}
