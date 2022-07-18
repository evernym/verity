package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.vdrtools.IndyException
import com.evernym.verity.actor.agent.relationship
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
                          _vdrLedgerPrefixMappings: Map[LedgerPrefix, LedgerPrefix])
                         (implicit val asyncOpRunner: AsyncOpRunner,
                          implicit val asyncAPIContext: AsyncAPIContext,
                          implicit val ec: ExecutionContext)
  extends LedgerAccess
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

  override def resolveDidDoc(fqDid: FqDID)(handler: Try[relationship.DidDoc] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {
        getCachedItem[FqDID](fqDid)
          .map(s => Future.successful(s))
          .getOrElse(fetchAndCacheDidDoc(fqDid))
      },
      handleAsyncOpResult(handler)
    )

  override def resolveDidDocs(fqDids: Set[FqDID])(handler: Try[Seq[relationship.DidDoc]] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {
        Future
          .sequence(fqDids.map { id =>
            getCachedItem[FqDID](id).map(s => Future.successful(s)).getOrElse(
              fetchAndCacheSchema(id))
          })
          .map(_.toSeq)
      },
      handleAsyncOpResult(handler)
    )
  }

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

  override def fqDID(did: DidStr): FqDID = {
    VDRUtil.toFqDID(did, _vdrUnqualifiedLedgerPrefix, _vdrLedgerPrefixMappings)
  }

  override def fqSchemaId(schemaId: SchemaId,
                          issuerFqDID: Option[FqDID]): FqSchemaId  = {
    VDRUtil.toFqSchemaId_v0(schemaId, issuerFqDID, Option(_vdrUnqualifiedLedgerPrefix))
  }

  override def fqCredDefId(credDefId: CredDefId,
                           issuerFqDID: Option[FqDID]): FqCredDefId = {
    VDRUtil.toFqCredDefId_v0(credDefId, issuerFqDID, Option(_vdrUnqualifiedLedgerPrefix))
  }

  override def extractLedgerPrefix(submitterFqDID: FqDID,
                                   endorserFqDID: FqDID): LedgerPrefix = {
    val submitterLedgerPrefix = VDRUtil.extractLedgerPrefix(submitterFqDID)
    val endorserLedgerPrefix = Try(VDRUtil.extractLedgerPrefix(endorserFqDID)).getOrElse("")
    if (endorserLedgerPrefix.isEmpty || submitterLedgerPrefix == endorserLedgerPrefix) submitterLedgerPrefix
    else throw new RuntimeException(s"submitter ledger prefix '$submitterLedgerPrefix' not matched with endorser ledger prefix '$endorserLedgerPrefix'")
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

  private def fetchAndCacheDidDoc(id: String): Future[DidDoc] = {
    vdrTools.resolveDID(id, None).map { dd =>
      vdrCache.put(id, dd)
      dd
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
