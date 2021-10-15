package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerRequest, TxnResp}
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AsyncOpRunner, BaseAccessController, LedgerReadAccess, LedgerWriteAccess}
import com.evernym.verity.vdr.{CredDef, FQCredDefId, FQSchemaId, PreparedTxn, Schema, SubmittedTxn, VDRAdapter}

import scala.util.Try

class LedgerAccessController(val accessRights: Set[AccessRight],
                             vdrTools: VDRAdapter,
                             ledgerExecutor: LedgerAsyncOps)
                            (implicit val asyncOpRunner: AsyncOpRunner)
  extends LedgerAccess
    with BaseAccessController {

  override def walletAccess: WalletAccess = ledgerExecutor.walletAccess

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
    runIfAllowed(LedgerWriteAccess, {
      vdrTools.prepareSchemaTxn(schemaJson, fqSchemaId, submitterDID, endorser)}, handler
    )


  override def prepareCredDefTxn(credDefJson: String,
                                 fqCredDefId: FQCredDefId,
                                 submitterDID: DidStr,
                                 endorser: Option[String])
                                (handler: Try[PreparedTxn] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess,
      { vdrTools.prepareCredDefTxn(credDefJson, fqCredDefId, submitterDID, endorser) },
      handler
    )

  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte])
                        (handler: Try[SubmittedTxn] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {
      vdrTools.submitTxn(preparedTxn, signature, endorsement)}, handler
    )

  override def resolveSchema(fqSchemaId: FQSchemaId)(handler: Try[Schema] => Unit): Unit =
    runIfAllowed(LedgerReadAccess, { vdrTools.resolveSchema(fqSchemaId) }, handler)
  override def resolveCredDef(fqCredDefId: FQCredDefId)(handler: Try[CredDef] => Unit): Unit =
    runIfAllowed(LedgerReadAccess, { vdrTools.resolveCredDef(fqCredDefId) }, handler)
}
