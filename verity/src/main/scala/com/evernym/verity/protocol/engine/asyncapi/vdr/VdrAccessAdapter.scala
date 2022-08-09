package com.evernym.verity.protocol.engine.asyncapi.vdr

import com.evernym.verity.did.DidStr
import com.evernym.vdrtools.IndyException
import com.evernym.verity.cache.providers.CacheProvider
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.asyncapi._
import com.evernym.verity.vdr._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}


class VdrAccessAdapter(vdrTools: VDRAdapter,
                       vdrCache: CacheProvider,
                       _isMultiLedgerSupportEnabled: Boolean,
                       _unqualifiedLedgerPrefix: LedgerPrefix,
                       _ledgerPrefixMappings: Map[LedgerPrefix, LedgerPrefix])
                      (implicit val asyncOpRunner: AsyncOpRunner,
                       val asyncAPIContext: AsyncAPIContext,
                       val ec: ExecutionContext)
  extends VdrAccess
    with AsyncResultHandler {

  override def prepareSchemaTxn(schemaJson: String,
                                schemaId: SchemaId,
                                submitterDID: FqDID,
                                endorser: Option[String])
                               (handler: Try[PreparedTxn] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.prepareSchemaTxn(schemaJson, schemaId, submitterDID, endorser)},
      handleAsyncOpResult(handler)
    )
  }

  override def prepareCredDefTxn(credDefJson: String,
                                 credDefId: CredDefId,
                                 submitterDID: FqDID,
                                 endorser: Option[String])
                                (handler: Try[PreparedTxn] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.prepareCredDefTxn(credDefJson, credDefId, submitterDID, endorser)},
      handleAsyncOpResult(handler)
    )

  override def prepareDidTxn(didJson: String,
                             submitterDID: FqDID,
                             endorser: Option[String])
                            (handler: Try[PreparedTxn] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.prepareDidTxn(didJson, submitterDID, endorser)},
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

  override def fqDID(did: DidStr,
                     force: Boolean): FqDID = {
    VDRUtil.toFqDID(did, force || _isMultiLedgerSupportEnabled, _unqualifiedLedgerPrefix, _ledgerPrefixMappings)
  }

  override def fqSchemaId(schemaId: SchemaId,
                          issuerFqDID: Option[FqDID],
                          force: Boolean): FqSchemaId  = {
    VDRUtil.toFqSchemaId_v0(schemaId, issuerFqDID, Option(_unqualifiedLedgerPrefix), force || _isMultiLedgerSupportEnabled)
  }

  override def fqCredDefId(credDefId: CredDefId,
                           issuerFqDID: Option[FqDID],
                           force: Boolean): FqCredDefId = {
    VDRUtil.toFqCredDefId_v0(credDefId, issuerFqDID, Option(_unqualifiedLedgerPrefix), force || _isMultiLedgerSupportEnabled)
  }

  def toLegacyNonFqId(did: DidStr): DidStr = {
    VDRUtil.toLegacyNonFqDid(did, _isMultiLedgerSupportEnabled)
  }

  def toLegacyNonFqSchemaId(schemaId: FqSchemaId): SchemaId = {
    VDRUtil.toLegacyNonFqSchemaId(schemaId, _isMultiLedgerSupportEnabled)
  }

  def toLegacyNonFqCredDefId(credDefId: FqCredDefId): CredDefId = {
    VDRUtil.toLegacyNonFqCredDefId(credDefId, _isMultiLedgerSupportEnabled)
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

  override def handleResult[T](result: Try[Any], handler: Try[T] => Unit): Unit = {
    handler(
      result match {
        case Failure(ex: IndyException) =>
          //NOTE: `ex.getSdkMessage` provides actual error message and hence it should be used instead of just e.getMessage etc.
          Failure(VdrRejectException(ex.getSdkMessage))
        case other =>
          other.map(_.asInstanceOf[T])
      }
    )
  }

  override lazy val isMultiLedgerSupportEnabled: Boolean = _isMultiLedgerSupportEnabled
  override lazy val unqualifiedLedgerPrefix: LedgerPrefix = _unqualifiedLedgerPrefix
}
