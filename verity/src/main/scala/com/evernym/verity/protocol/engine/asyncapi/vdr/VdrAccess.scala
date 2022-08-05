package com.evernym.verity.protocol.engine.asyncapi.vdr

import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.{CredDef, CredDefId, FqCredDefId, FqDID, FqSchemaId, PreparedTxn, Schema, SchemaId, SubmittedTxn}

import scala.util.Try


trait VdrAccess {

  def isMultiLedgerSupportEnabled: Boolean

  def unqualifiedLedgerPrefix(): String

  //new vdr apis
  def prepareSchemaTxn(schemaJson: String,
                       schemaId: SchemaId,
                       submitterDID: FqDID,
                       endorser: Option[String])
                      (handler: Try[PreparedTxn] => Unit): Unit

  def prepareCredDefTxn(credDefJson: String,
                        credDefId: CredDefId,
                        submitterDID: FqDID,
                        endorser: Option[String])
                       (handler: Try[PreparedTxn] => Unit): Unit

  def prepareDidTxn(didJson: String,
                    submitterDID: FqDID,
                    endorser: Option[String])
                   (handler: Try[PreparedTxn] => Unit): Unit

  def submitTxn(preparedTxn: PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte])
               (handler: Try[SubmittedTxn] => Unit): Unit

  def resolveSchema(fqSchemaId: FqSchemaId)
                   (handler: Try[Schema] => Unit): Unit

  def resolveSchemas(fqSchemaIds: Set[FqSchemaId])
                    (handler: Try[Seq[Schema]] => Unit): Unit

  def resolveCredDef(fqCredDefId: FqCredDefId)
                    (handler: Try[CredDef] => Unit): Unit

  def resolveCredDefs(fqCredDefIds: Set[FqCredDefId])
                     (handler: Try[Seq[CredDef]] => Unit): Unit

  def fqDID(did: DidStr,
            force: Boolean): FqDID

  def fqSchemaId(schemaId: SchemaId,
                 issuerFqDID: Option[FqDID],
                 force: Boolean): FqSchemaId

  def fqCredDefId(credDefId: CredDefId,
                  issuerFqDID: Option[FqDID],
                  force: Boolean): FqCredDefId

  def toLegacyNonFqId(did: DidStr): DidStr

  def toLegacyNonFqSchemaId(schemaId: FqSchemaId): SchemaId

  def toLegacyNonFqCredDefId(credDefId: FqCredDefId): CredDefId
}

case class VdrRejectException(msg: String) extends Exception(msg)
case class VdrAccessException(msg: String) extends Exception(msg)

