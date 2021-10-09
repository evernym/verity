package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.vdr.{FQSchemaId, PreparedTxn}

trait LedgerAsyncOps {

  def walletAccess: WalletAccess

  def runGetSchema(schemaId: String): Unit

  def runGetCredDef(credDefId: String): Unit

  def runGetSchemas(schemaIds: Set[String]): Unit

  def runGetCredDefs(credDefIds: Set[String]): Unit

  def runWriteSchema(submitterDID: DidStr, schemaJson: String): Unit

  def runPrepareSchemaForEndorsement(submitterDID: DidStr, schemaJson: String, endorserDID: DidStr): Unit

  def runWriteCredDef(submitterDID: DidStr, credDefJson: String): Unit

  def runPrepareCredDefForEndorsement(submitterDID: DidStr, credDefJson: String, endorserDID: DidStr): Unit

  def prepareSchemaTxn(schemaJson: String,
                       fqSchemaId: FQSchemaId,
                       submitterDID: DidStr,
                       endorser: Option[String]): Unit

  def submitTxn(preparedTxn: PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte]): Unit
}
