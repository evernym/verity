package com.evernym.verity.protocol.engine.external_api_access

import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerRequest, TxnResp}
import com.evernym.verity.protocol.engine.DID

import scala.util.{Failure, Try}

class LedgerAccessController(accessRights: Set[AccessRight], ledgerRequestsImp: LedgerAccess)
  extends LedgerAccess {

  override def walletAccess: WalletAccess = ledgerRequestsImp.walletAccess

  def runIfAllowed[T](right: AccessRight, f: => Try[T]): Try[T] = {
    if (accessRights(right))
      f
    else
      Failure(new IllegalAccessException)
  }

  override def getCredDef(credDefId: String): Try[GetCredDefResp] =
    runIfAllowed(LedgerReadAccess, {ledgerRequestsImp.getCredDef(credDefId)})

  override def getSchema(schemaId: String): Try[GetSchemaResp] =
    runIfAllowed(LedgerReadAccess, {ledgerRequestsImp.getSchema(schemaId)})

  override def writeSchema(submitterDID: String, schemaJson: String): Try[Either[StatusDetail, TxnResp]] =
    runIfAllowed(LedgerWriteAccess, {ledgerRequestsImp.writeSchema(submitterDID, schemaJson)})

  override def prepareSchemaForEndorsement(submitterDID: DID, schemaJson: String, endorserDID: DID): Try[LedgerRequest] =
    runIfAllowed(LedgerWriteAccess, {ledgerRequestsImp.prepareSchemaForEndorsement(submitterDID, schemaJson, endorserDID)})

  override def writeCredDef(submitterDID: DID, credDefJson: String): Try[Either[StatusDetail, TxnResp]] =
    runIfAllowed(LedgerWriteAccess, {ledgerRequestsImp.writeCredDef(submitterDID, credDefJson)})

  override def prepareCredDefForEndorsement(submitterDID: DID, credDefJson: String, endorserDID: DID): Try[LedgerRequest] =
    runIfAllowed(LedgerWriteAccess, {ledgerRequestsImp.prepareCredDefForEndorsement(submitterDID, credDefJson, endorserDID)})
}
