package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerRequest, TxnResp}
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.BaseAsyncOpExecutorImpl
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AsyncOpRunner, BaseAccessController, LedgerReadAccess, LedgerWriteAccess}
import com.evernym.verity.vdr.{CredDef, FQCredDefId, FQSchemaId, PreparedTxn, Schema, SubmittedTxn, VDRAdapter}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

class LedgerAccessController(val accessRights: Set[AccessRight],
                             vdrTools: VDRAdapter,
                             ledgerExecutor: LedgerAsyncOps)
                            (implicit val asyncOpRunner: AsyncOpRunner,
                             implicit val asyncAPIContext: AsyncAPIContext)
  extends LedgerAccess
    with BaseAsyncOpExecutorImpl
    with BaseAccessController {

  override def walletAccess: WalletAccess = ledgerExecutor.walletAccess

  //TODO: possibly it doesn't work, need resolve this
  private def vdrAsyncRunIfAllowed[T](accessRight: AccessRight, op: ExecutionContext => Future[T], handler: Try[T] => Unit): Unit = {
    if (accessRights(accessRight)) {
      withAsyncOpExecutorActor(op)
    }
    else {
      handler(Failure(new IllegalAccessException))
    }

  }

  def getSchema(schemaId: String)(handler: Try[GetSchemaResp] => Unit): Unit = {
    runIfAllowed(LedgerReadAccess, {ledgerExecutor.runGetSchema(schemaId)}, handler)
  }

  def getCredDef(credDefId: String)(handler: Try[GetCredDefResp] => Unit): Unit =
    runIfAllowed(LedgerReadAccess, {ledgerExecutor.runGetCredDef(credDefId)}, handler)

  override def getSchemas(schemaIds: Set[String])
                         (handler: Try[Map[String, GetSchemaResp]] => Unit): Unit =
    runIfAllowed(LedgerReadAccess, {ledgerExecutor.runGetSchemas(schemaIds)}, handler)

  override def getCredDefs(credDefIds: Set[String])
                          (handler: Try[Map[String, GetCredDefResp]] => Unit): Unit =
    runIfAllowed(LedgerReadAccess, {ledgerExecutor.runGetCredDefs(credDefIds)}, handler)

  override def writeSchema(submitterDID: DidStr, schemaJson: String)
                          (handler: Try[Either[StatusDetail, TxnResp]] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerExecutor.runWriteSchema(submitterDID, schemaJson)}, handler)

  override def prepareSchemaForEndorsement(submitterDID: DidStr, schemaJson: String, endorserDID: DidStr)
                                          (handler: Try[LedgerRequest] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerExecutor.runPrepareSchemaForEndorsement(
      submitterDID, schemaJson, endorserDID)}, handler)

  override def writeCredDef(submitterDID: DidStr, credDefJson: String)
                           (handler: Try[Either[StatusDetail, TxnResp]] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerExecutor.runWriteCredDef(submitterDID, credDefJson)}, handler)

  override def prepareCredDefForEndorsement(submitterDID: DidStr, credDefJson: String, endorserDID: DidStr)
                                           (handler: Try[LedgerRequest] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerExecutor.runPrepareCredDefForEndorsement(
      submitterDID, credDefJson, endorserDID)}, handler)

  override def prepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FQSchemaId,
                                submitterDID: DidStr,
                                endorser: Option[String])
                               (handler: Try[PreparedTxn] => Unit): Unit =
    vdrAsyncRunIfAllowed(LedgerReadAccess, { implicit ex =>
      Future {
        vdrTools.prepareSchemaTxn(schemaJson, fqSchemaId, submitterDID, endorser).onComplete(handler)
      }
    }, handler)


  override def prepareCredDefTxn(credDefJson: String,
                                 fqCredDefId: FQCredDefId,
                                 submitterDID: DidStr,
                                 endorser: Option[String])
                                (handler: Try[PreparedTxn] => Unit): Unit =
    vdrAsyncRunIfAllowed(LedgerReadAccess, { implicit ex =>
      Future {
        vdrTools.prepareCredDefTxn(credDefJson, fqCredDefId, submitterDID, endorser).onComplete(handler)
      }
    }, handler)

  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte])
                        (handler: Try[SubmittedTxn] => Unit): Unit =
    vdrAsyncRunIfAllowed(LedgerReadAccess, { implicit ex =>
      Future {
        vdrTools.submitTxn(preparedTxn, signature, endorsement).onComplete(handler)
      }
    }, handler)

  override def resolveSchema(fqSchemaId: FQSchemaId)(handler: Try[Schema] => Unit): Unit =
    vdrAsyncRunIfAllowed(LedgerReadAccess, { implicit ex =>
      Future {
        vdrTools.resolveSchema(fqSchemaId).onComplete(handler)
      }
    }, handler)

  override def resolveCredDef(fqCredDefId: FQCredDefId)(handler: Try[CredDef] => Unit): Unit = {

    vdrAsyncRunIfAllowed(LedgerReadAccess, { implicit ex =>
      Future {
        vdrTools.resolveCredDef(fqCredDefId).onComplete(handler)
      }
    }, handler)
  }
}
