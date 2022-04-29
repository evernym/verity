package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.cache.base.{Cache, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.cache.fetchers.{GetCredDef, GetSchema}
import com.evernym.verity.cache.{LEDGER_GET_CRED_DEF_FETCHER, LEDGER_GET_SCHEMA_FETCHER}
import com.evernym.verity.did.DidStr
import com.evernym.verity.ledger._
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.asyncapi._
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.util2.Exceptions.NotFoundErrorException
import com.evernym.verity.vdr._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class LedgerAccessAdapter(vdrTools: VDRAdapter,
                          cache: Cache,
                          ledgerSvc: LedgerSvc,
                          _walletAccess: WalletAccess)
                         (implicit val asyncOpRunner: AsyncOpRunner,
                          implicit val asyncAPIContext: AsyncAPIContext,
                          implicit val ec: ExecutionContext)
  extends LedgerAccess {

  def getSchema(schemaId: String)(handler: Try[GetSchemaResp] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {getSchemaBase(Set(schemaId)).map { r => r(schemaId) }},
      handler
    )
  }

  def getCredDef(credDefId: String)(handler: Try[GetCredDefResp] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {getCredDefsBase(Set(credDefId)).map { r => r(credDefId)} },
      handler
    )
  }

  override def getSchemas(schemaIds: Set[String])
                         (handler: Try[Map[String, GetSchemaResp]] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {getSchemaBase(schemaIds)},
      handler
    )
  }

  override def getCredDefs(credDefIds: Set[String])
                          (handler: Try[Map[String, GetCredDefResp]] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {getCredDefsBase(credDefIds)},
      handler
    )
  }

  override def writeSchema(submitterDID: DidStr, schemaJson: String)(handler: Try[TxnResp] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {ledgerSvc.writeSchema(submitterDID, schemaJson, walletAccess)},
      handler
    )
  }

  override def prepareSchemaForEndorsement(submitterDID: DidStr, schemaJson: String, endorserDID: DidStr)
                                          (handler: Try[LedgerRequest] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {ledgerSvc.prepareSchemaForEndorsement(submitterDID, schemaJson, endorserDID, walletAccess)},
      handler
    )

  override def writeCredDef(submitterDID: DidStr, credDefJson: String)(handler: Try[TxnResp] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {ledgerSvc.writeCredDef(submitterDID, credDefJson, walletAccess)},
      handler
    )
  }

  override def prepareCredDefForEndorsement(submitterDID: DidStr, credDefJson: String, endorserDID: DidStr)
                                           (handler: Try[LedgerRequest] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {ledgerSvc.prepareCredDefForEndorsement(submitterDID, credDefJson, endorserDID, walletAccess)},
      handler
    )
  }



  override def prepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FQSchemaId,
                                submitterDID: DidStr,
                                endorser: Option[String])
                               (handler: Try[PreparedTxn] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.prepareSchemaTxn(schemaJson, fqSchemaId, submitterDID, endorser)},
      handler
    )
  }


  override def prepareCredDefTxn(credDefJson: String,
                                 fqCredDefId: FQCredDefId,
                                 submitterDID: DidStr,
                                 endorser: Option[String])
                                (handler: Try[PreparedTxn] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.prepareCredDefTxn(credDefJson, fqCredDefId, submitterDID, endorser)},
      handler
    )

  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte])
                        (handler: Try[SubmittedTxn] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.submitTxn(preparedTxn, signature, endorsement)},
      handler
    )


  override def resolveSchema(fqSchemaId: FQSchemaId)(handler: Try[Schema] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.resolveSchema(fqSchemaId)},
      handler
    )

  override def resolveCredDef(fqCredDefId: FQCredDefId)(handler: Try[CredDef] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.resolveCredDef(fqCredDefId)},
      handler
    )

  private def getSchemaBase(schemaIds: Set[String])(implicit ec: ExecutionContext): Future[Map[String, GetSchemaResp]] = {
    val keyDetails = schemaIds.map { sId =>
      KeyDetail(GetSchema(sId), required = true)
    }
    val gcop = GetCachedObjectParam(keyDetails, LEDGER_GET_SCHEMA_FETCHER)
    cache.getByParamAsync(gcop).map { cqr =>
      val result = schemaIds.map { sId => sId -> cqr.getReq[GetSchemaResp](sId) }.toMap
      if (result.keySet == schemaIds) {
        result
      } else {
        throw new NotFoundErrorException(s"schemas not found for ids: ${schemaIds.diff(result.keySet)}")
      }
    }.recover {
      case e: Throwable => throw LedgerAccessException(e.getMessage)
    }
  }

  private def getCredDefsBase(credDefIds: Set[String])(implicit ec: ExecutionContext): Future[Map[String, GetCredDefResp]] = {
    val keyDetails = credDefIds.map { cId =>
      KeyDetail(GetCredDef(cId), required = true)
    }
    val gcop = GetCachedObjectParam(keyDetails, LEDGER_GET_CRED_DEF_FETCHER)
    cache.getByParamAsync(gcop).map { cqr =>
      val result = credDefIds.map { cId => cId -> cqr.getReq[GetCredDefResp](cId) }.toMap
      if (result.keySet == credDefIds) {
        result
      } else {
        throw new NotFoundErrorException(s"cred defs not found for ids: ${credDefIds.diff(result.keySet)}")
      }
    }.recover {
      case e: Throwable => throw LedgerAccessException(e.getMessage)
    }
  }

  override def walletAccess: WalletAccess = _walletAccess

  override lazy val getIndyDefaultLegacyPrefix: String = ledgerSvc.getIndyDefaultLegacyPrefix()
}
