package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.did.DID
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess

trait LedgerAsyncOps {

  def walletAccess: WalletAccess

  def runGetSchema(schemaId: String): Unit

  def runGetCredDef(credDefId: String): Unit

  def runGetSchemas(schemaIds: Set[String]): Unit

  def runGetCredDefs(credDefIds: Set[String]): Unit

  def runWriteSchema(submitterDID: DID, schemaJson: String): Unit

  def runPrepareSchemaForEndorsement(submitterDID: DID, schemaJson: String, endorserDID: DID): Unit

  def runWriteCredDef(submitterDID: DID, credDefJson: String): Unit

  def runPrepareCredDefForEndorsement(submitterDID: DID, credDefJson: String, endorserDID: DID): Unit

}
