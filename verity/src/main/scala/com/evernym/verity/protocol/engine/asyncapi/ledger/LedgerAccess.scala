package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.vdr.{CacheOption, CredDef, FqCredDefId, FqDID, FqSchemaId, PreparedTxn, Schema, SubmittedTxn}

import scala.util.Try


trait LedgerAccess {

  def walletAccess: WalletAccess

  def getIndyDefaultLegacyPrefix(): String

  //new vdr apis
  def prepareSchemaTxn(schemaJson: String,
                       fqSchemaId: FqSchemaId,
                       submitterDID: DidStr,
                       endorser: Option[String])
                      (handler: Try[PreparedTxn] => Unit): Unit

  def prepareCredDefTxn(credDefJson: String,
                        fqCredDefId: FqCredDefId,
                        submitterDID: DidStr,
                        endorser: Option[String])
                       (handler: Try[PreparedTxn] => Unit): Unit

  def submitTxn(preparedTxn: PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte])
               (handler: Try[SubmittedTxn] => Unit): Unit

  def resolveSchema(fqSchemaId: FqSchemaId,
                    cacheOption: Option[CacheOption]=None)
                   (handler: Try[Schema] => Unit): Unit

  def resolveSchemas(fqSchemaIds: Set[FqSchemaId],
                     cacheOption: Option[CacheOption]=None)
                    (handler: Try[Seq[Schema]] => Unit): Unit

  def resolveCredDef(fqCredDefId: FqCredDefId,
                     cacheOption: Option[CacheOption]=None)
                    (handler: Try[CredDef] => Unit): Unit

  def resolveCredDefs(fqCredDefIds: Set[FqCredDefId],
                      cacheOption: Option[CacheOption]=None)
                     (handler: Try[Seq[CredDef]] => Unit): Unit

  def fqDID(did: String): FqDID

  def fqSchemaId(schemaId: String, issuerFqDID: Option[FqDID]): FqSchemaId

  def fqCredDefId(credDefId: String, issuerFqDID: Option[FqDID]): FqCredDefId
}

case class LedgerRejectException(msg: String) extends Exception(msg)
case class LedgerAccessException(msg: String) extends Exception(msg)

