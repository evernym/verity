package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.vdrtools.ledger.LedgerInvalidTransactionException
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.asyncapi._
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.vdr._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

class LedgerAccessAdapter(vdrTools: VDRAdapter,
                          _walletAccess: WalletAccess,
                          _vdrDefaultNamespace: Namespace)
                         (implicit val asyncOpRunner: AsyncOpRunner,
                          implicit val asyncAPIContext: AsyncAPIContext,
                          implicit val ec: ExecutionContext)
  extends LedgerAccess
    with AsyncResultHandler {

  override def prepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FQSchemaId,
                                submitterDID: DidStr,
                                endorser: Option[String])
                               (handler: Try[PreparedTxn] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.prepareSchemaTxn(schemaJson, fqSchemaId, submitterDID, endorser)},
      handleAsyncOpResult(handler)
    )
  }


  override def prepareCredDefTxn(credDefJson: String,
                                 fqCredDefId: FQCredDefId,
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


  override def resolveSchema(fqSchemaId: FQSchemaId,
                             cacheOption: Option[CacheOption]=None)(handler: Try[Schema] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.resolveSchema(fqSchemaId)},
      handleAsyncOpResult(handler)
    )
  }

  override def resolveCredDef(fqCredDefId: FQCredDefId,
                              cacheOption: Option[CacheOption]=None)(handler: Try[CredDef] => Unit): Unit =
    asyncOpRunner.withFutureOpRunner(
      {vdrTools.resolveCredDef(fqCredDefId, cacheOption)},
      handleAsyncOpResult(handler)
    )

  override def resolveSchemas(fqSchemaIds: Set[FQSchemaId],
                              cacheOption: Option[CacheOption]=None)(handler: Try[Seq[Schema]] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {Future.sequence(fqSchemaIds.map(id => vdrTools.resolveSchema(id, cacheOption))).map(_.toSeq)},
      handleAsyncOpResult(handler)
    )
  }

  override def resolveCredDefs(fqCredDefIds: Set[FQCredDefId],
                               cacheOption: Option[CacheOption]=None)(handler: Try[Seq[CredDef]] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      {Future.sequence(fqCredDefIds.map(id => vdrTools.resolveCredDef(id, cacheOption))).map(_.toSeq)},
      handleAsyncOpResult(handler)
    )
  }

  override def fqID(id: String): FQDid = {
    LedgerUtil.toFQId(id, _vdrDefaultNamespace)
  }

  override def fqSchemaId(id: String): FQSchemaId  = {
    LedgerUtil.toFQSchemaId(id, _vdrDefaultNamespace)
  }

  override def fqCredDefId(id: String): FQCredDefId = {
    LedgerUtil.toFQCredDefId(id, _vdrDefaultNamespace)
  }

  override def walletAccess: WalletAccess = _walletAccess

  //TODO: how to avoid dependency on libvdrtools exceptions here?
  override def handleResult[T](result: Try[Any], handler: Try[T] => Unit): Unit = {
    //TODO: fix error handling once new libvdrtools is available
    handler(
      result match {
        case Failure(ex: LedgerInvalidTransactionException) =>
          Failure(LedgerRejectException(ex.getMessage))
        case other      =>
          other.map(_.asInstanceOf[T])
      }
    )
  }
}
