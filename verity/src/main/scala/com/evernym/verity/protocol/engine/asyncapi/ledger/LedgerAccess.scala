package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerRequest, TxnResp}
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.vdr.{CredDef, FQCredDefId, FQSchemaId, PreparedTxn, Schema, SubmittedTxn}

import scala.util.Try

trait LedgerAccess {

  def walletAccess: WalletAccess

  def getSchema(schemaId: String)(handler: Try[GetSchemaResp] => Unit): Unit

  def getCredDef(credDefId: String)(handler: Try[GetCredDefResp] => Unit): Unit

  def getSchemas(schemaIds: Set[String])
               (handler: Try[Map[String, GetSchemaResp]] => Unit): Unit

  def getCredDefs(credDefIds: Set[String])
                (handler: Try[Map[String, GetCredDefResp]] => Unit): Unit

  def writeSchema(submitterDID: DidStr, schemaJson: String)(handler: Try[TxnResp] => Unit): Unit

  def prepareSchemaForEndorsement(submitterDID: DidStr, schemaJson: String, endorserDID: DidStr)
                                 (handler: Try[LedgerRequest] => Unit): Unit

  def writeCredDef(submitterDID: DidStr, credDefJson: String)(handler: Try[TxnResp] => Unit): Unit

  def prepareCredDefForEndorsement(submitterDID: DidStr, credDefJson: String, endorserDID: DidStr)
                                  (handler: Try[LedgerRequest] => Unit): Unit

  //new vdr apis
  def prepareSchemaTxn(schemaJson: String,
                       fqSchemaId: FQSchemaId,
                       submitterDID: DidStr,
                       endorser: Option[String])
                      (handler: Try[PreparedTxn] => Unit): Unit

  def prepareCredDefTxn(credDefJson: String,
                        fqCredDefId: FQCredDefId,
                        submitterDID: DidStr,
                        endorser: Option[String])
                       (handler: Try[PreparedTxn] => Unit): Unit

  def submitTxn(preparedTxn: PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte])
               (handler: Try[SubmittedTxn] => Unit): Unit

  def resolveSchema(fqSchemaId: FQSchemaId)
                   (handler: Try[Schema] => Unit): Unit

  def resolveCredDef(fqCredDefId: FQCredDefId)
                    (handler: Try[CredDef] => Unit): Unit
}

case class LedgerRejectException(msg: String) extends Exception(msg)
case class LedgerAccessException(msg: String) extends Exception(msg)

