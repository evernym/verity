package com.evernym.verity.protocol.engine.asyncService.ledger

import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerRequest, TxnResp}
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.asyncService.wallet.WalletAccess
import com.evernym.verity.protocol.engine.asyncService.{AccessRight, LedgerReadAccess, LedgerWriteAccess}

import scala.util.{Failure, Try}

class LedgerAccessController(accessRights: Set[AccessRight],
                             ledgerRequestsImp: LedgerAccess)
  extends LedgerAccess {

  override def walletAccess: WalletAccess = ledgerRequestsImp.walletAccess

  def runIfAllowed[T](right: AccessRight, f: (Try[T] => Unit) => Unit, handler: Try[T] => Unit): Unit =
    if(accessRights(right))
      f(handler)
    else
      handler(Failure(new IllegalAccessException))

  def getSchema(schemaId: String)(handler: Try[GetSchemaResp] => Unit): Unit =
    runIfAllowed(LedgerReadAccess, {ledgerRequestsImp.getSchema(schemaId)}, handler)

  def getCredDef(credDefId: String)(handler: Try[GetCredDefResp] => Unit): Unit =
    runIfAllowed(LedgerReadAccess, {ledgerRequestsImp.getCredDef(credDefId)}, handler)

  override def getSchemas(schemaIds: Set[String])
                         (handler: Try[Map[String, GetSchemaResp]] => Unit): Unit =
    runIfAllowed(LedgerReadAccess, {ledgerRequestsImp.getSchemas(schemaIds)}, handler)

  override def getCredDefs(credDefIds: Set[String])
                          (handler: Try[Map[String, GetCredDefResp]] => Unit): Unit =
    runIfAllowed(LedgerReadAccess, {ledgerRequestsImp.getCredDefs(credDefIds)}, handler)

  override def writeSchema(submitterDID: DID, schemaJson: String)
                          (handler: Try[Either[StatusDetail, TxnResp]] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerRequestsImp.writeSchema(submitterDID, schemaJson)}, handler)

  override def prepareSchemaForEndorsement(submitterDID: DID, schemaJson: String, endorserDID: DID)
                                          (handler: Try[LedgerRequest] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerRequestsImp.prepareSchemaForEndorsement(
      submitterDID, schemaJson, endorserDID)}, handler)

  override def writeCredDef(submitterDID: DID, credDefJson: String)
                           (handler: Try[Either[StatusDetail, TxnResp]] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerRequestsImp.writeCredDef(submitterDID, credDefJson)}, handler)

  override def prepareCredDefForEndorsement(submitterDID: DID, credDefJson: String, endorserDID: DID)
                                           (handler: Try[LedgerRequest] => Unit): Unit =
    runIfAllowed(LedgerWriteAccess, {ledgerRequestsImp.prepareCredDefForEndorsement(
      submitterDID, credDefJson, endorserDID)}, handler)
}
