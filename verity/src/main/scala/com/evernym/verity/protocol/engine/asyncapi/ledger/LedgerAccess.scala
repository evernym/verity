package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.vdr.{CacheOption, CredDef, FQCredDefId, FQDid, FQSchemaId, PreparedTxn, Schema, SubmittedTxn}

import scala.util.Try


trait LedgerAccess {

  def walletAccess: WalletAccess

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

  def resolveSchema(fqSchemaId: FQSchemaId,
                    cacheOption: Option[CacheOption]=None)
                   (handler: Try[Schema] => Unit): Unit

  def resolveSchemas(fqSchemaIds: Set[FQSchemaId],
                     cacheOption: Option[CacheOption]=None)
                    (handler: Try[Seq[Schema]] => Unit): Unit

  def resolveCredDef(fqCredDefId: FQCredDefId,
                     cacheOption: Option[CacheOption]=None)
                    (handler: Try[CredDef] => Unit): Unit

  def resolveCredDefs(fqCredDefIds: Set[FQCredDefId],
                      cacheOption: Option[CacheOption]=None)
                     (handler: Try[Seq[CredDef]] => Unit): Unit

  def fqID(id: String): FQDid

  def fqSchemaId(id: String): FQSchemaId

  def fqCredDefId(id: String): FQCredDefId
}

case class LedgerRejectException(msg: String) extends Exception(msg)
case class LedgerAccessException(msg: String) extends Exception(msg)

