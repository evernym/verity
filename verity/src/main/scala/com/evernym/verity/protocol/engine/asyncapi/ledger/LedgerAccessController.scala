package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerRequest, TxnResp}
import com.evernym.verity.protocol.container.asyncapis.ledger.LedgerAccessAPI
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AsyncOpRunner, BaseAccessController, LedgerReadAccess, LedgerWriteAccess}

import scala.util.Try

class LedgerAccessController(val accessRights: Set[AccessRight],
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

  override def writeSchema(submitterDID: DID, schemaJson: String)
                          (handler: Try[Either[StatusDetail, TxnResp]] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerExecutor.runWriteSchema(submitterDID, schemaJson)}, handler)

  override def prepareSchemaForEndorsement(submitterDID: DID, schemaJson: String, endorserDID: DID)
                                          (handler: Try[LedgerRequest] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerExecutor.runPrepareSchemaForEndorsement(
      submitterDID, schemaJson, endorserDID)}, handler)

  override def writeCredDef(submitterDID: DID, credDefJson: String)
                           (handler: Try[Either[StatusDetail, TxnResp]] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerExecutor.runWriteCredDef(submitterDID, credDefJson)}, handler)

  override def prepareCredDefForEndorsement(submitterDID: DID, credDefJson: String, endorserDID: DID)
                                           (handler: Try[LedgerRequest] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerExecutor.runPrepareCredDefForEndorsement(
      submitterDID, credDefJson, endorserDID)}, handler)
}
