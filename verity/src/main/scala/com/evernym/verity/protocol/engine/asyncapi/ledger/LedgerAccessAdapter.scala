package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.vdrtools.IndyException
import com.evernym.verity.cache.providers.CacheProvider
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.asyncapi._
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.vdr._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}


class LedgerAccessAdapter(vdrTools: VDRAdapter,
                          vdrCache: CacheProvider,
                          _walletAccess: WalletAccess,
                          _vdrUnqualifiedLedgerPrefix: LedgerPrefix,
                          _vdrLegacyLedgerPrefixMappings: Map[LedgerPrefix, LedgerPrefix])
                         (implicit val asyncOpRunner: AsyncOpRunner,
                          implicit val asyncAPIContext: AsyncAPIContext,
                          implicit val ec: ExecutionContext)
  extends LedgerAccess
    with AsyncResultHandler {

  override def prepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FqSchemaId,
                                submitterDID: DidStr,
                                endorser: Option[String])
                               (handler: Try[PreparedTxn] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.prepareSchemaTxn(schemaJson, fqSchemaId, submitterDID, endorser)},
      handleAsyncOpResult(handler)
    )
  }


  override def prepareCredDefTxn(credDefJson: String,
                                 fqCredDefId: FqCredDefId,
                                 submitterDID: DidStr,
                                 endorser: Option[String])
                                (handler: Try[PreparedTxn] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.prepareCredDefTxn(credDefJson, fqCredDefId, submitterDID, endorser)},
      handleAsyncOpResult(handler)
    )

  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte])
                        (handler: Try[SubmittedTxn] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.submitTxn(preparedTxn, signature, endorsement)},
      handleAsyncOpResult(handler)
    )


  override def resolveSchema(fqSchemaId: FqSchemaId)(handler: Try[Schema] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {
        getCachedItem[Schema](fqSchemaId)
          .map(s => Future.successful(s))
          .getOrElse(fetchAndCacheSchema(fqSchemaId))
      },
      handleAsyncOpResult(handler)
    )
  }

  override def resolveCredDef(fqCredDefId: FqCredDefId)(handler: Try[CredDef] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {
        getCachedItem[CredDef](fqCredDefId)
          .map(s => Future.successful(s))
          .getOrElse(fetchAndCacheCredDef(fqCredDefId))
      },
      handleAsyncOpResult(handler)
    )

  override def resolveSchemas(fqSchemaIds: Set[FqSchemaId])(handler: Try[Seq[Schema]] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {
        Future
          .sequence(fqSchemaIds.map { id =>
            getCachedItem[Schema](id).map(s => Future.successful(s)).getOrElse(
              fetchAndCacheSchema(id))
            })
          .map(_.toSeq)
      },
      handleAsyncOpResult(handler)
    )
  }

  override def resolveCredDefs(fqCredDefIds: Set[FqCredDefId])(handler: Try[Seq[CredDef]] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {
        Future
          .sequence(fqCredDefIds.map{ id =>
            getCachedItem[CredDef](id).map(s => Future.successful(s))
              .getOrElse(fetchAndCacheCredDef(id))
          })
          .map(_.toSeq)
      },
      handleAsyncOpResult(handler)
    )
  }

  override def fqDID(did: String): FqDID = {
    VDRUtil.toFqDID(did, _vdrUnqualifiedLedgerPrefix, _vdrLegacyLedgerPrefixMappings)
  }

  override def fqSchemaId(schemaId: String,
                          issuerFqDID: Option[FqDID]): FqSchemaId  = {
    VDRUtil.toFqSchemaId_v0(schemaId, issuerFqDID, Option(_vdrUnqualifiedLedgerPrefix))
  }

  override def fqCredDefId(credDefId: String,
                           issuerFqDID: Option[FqDID]): FqCredDefId = {
    VDRUtil.toFqCredDefId_v0(credDefId, issuerFqDID, Option(_vdrUnqualifiedLedgerPrefix))
  }

  private def getCachedItem[T](id: String): Option[T] = {
    vdrCache.get(id).map(_.asInstanceOf[T])
  }

  private def fetchAndCacheSchema(id: String): Future[Schema] = {
    vdrTools.resolveSchema(id, None).map { s =>
      vdrCache.put(id, s)
      s
    }
  }

  private def fetchAndCacheCredDef(id: String): Future[CredDef] = {
    vdrTools.resolveCredDef(id, None).map { cd =>
      vdrCache.put(id, cd)
      cd
    }
  }

  override def walletAccess: WalletAccess = _walletAccess

  override def handleResult[T](result: Try[Any], handler: Try[T] => Unit): Unit = {
    handler(
      result match {
        case Failure(ex: IndyException) =>
          //NOTE: `ex.getSdkMessage` provides actual error message and hence it should be used instead of just e.getMessage etc.
          Failure(LedgerRejectException(ex.getSdkMessage))
        case other =>
          other.map(_.asInstanceOf[T])
      }
    )
  }

  override lazy val vdrUnqualifiedLedgerPrefix: String = _vdrUnqualifiedLedgerPrefix
}
