package com.evernym.verity.protocol.engine.external_api_access

import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerRequest, TxnResp}
import com.evernym.verity.protocol.engine.DID

import scala.util.Try

trait LedgerAccess {

  def walletAccess: WalletAccess
  
  def getCredDef(credDefId: String): Try[GetCredDefResp]

  def getSchema(schemaId: String): Try[GetSchemaResp]

  def writeSchema(submitterDID: DID, schemaJson: String): Try[Either[StatusDetail, TxnResp]]

  def prepareSchemaForEndorsement(submitterDID: DID, schemaJson: String, endorserDID: DID): Try[LedgerRequest]

  def writeCredDef(submitterDID: DID, credDefJson: String): Try[Either[StatusDetail, TxnResp]]

  def prepareCredDefForEndorsement(submitterDID: DID, credDefJson: String, endorserDID: DID): Try[LedgerRequest]
}

case class LedgerRejectException(msg: String) extends Exception(msg)
case class LedgerAccessException(msg: String) extends Exception(msg)

