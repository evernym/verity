package com.evernym.verity.protocol.engine.asyncapi

package object endorser {
  val VDR_TYPE_INDY = "indy"

  val INDY_LEDGER_PREFIX = "did:sov"
  val SUPPORTED_LEDGER_PREFIXES = List(INDY_LEDGER_PREFIX)

  val SUCCESSFUL_ENDORSEMENT_COMPLETED = "Success"
}
