package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerRequest, TxnResp}
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess

import scala.util.Try

trait LedgerAccess {

  def walletAccess: WalletAccess

  def getSchema(schemaId: String)(handler: Try[GetSchemaResp] => Unit): Unit

  def getCredDef(credDefId: String)(handler: Try[GetCredDefResp] => Unit): Unit

  def getSchemas(schemaIds: Set[String])
               (handler: Try[Map[String, GetSchemaResp]] => Unit): Unit

  def getCredDefs(credDefIds: Set[String])
                (handler: Try[Map[String, GetCredDefResp]] => Unit): Unit

  def writeSchema(submitterDID: DID, schemaJson: String)
                 (handler: Try[Either[StatusDetail, TxnResp]] => Unit): Unit

  def prepareSchemaForEndorsement(submitterDID: DID, schemaJson: String, endorserDID: DID)
                                 (handler: Try[LedgerRequest] => Unit): Unit

  def writeCredDef(submitterDID: DID, credDefJson: String)
                  (handler: Try[Either[StatusDetail, TxnResp]] => Unit): Unit

  def prepareCredDefForEndorsement(submitterDID: DID, credDefJson: String, endorserDID: DID)
                                  (handler: Try[LedgerRequest] => Unit): Unit
}

case class LedgerRejectException(msg: String) extends Exception(msg)
case class LedgerAccessException(msg: String) extends Exception(msg)

