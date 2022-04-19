package com.evernym.verity.protocol.engine.asyncapi

package object endorser {
  val VDR_TYPE_INDY = "indy"

  val INDY_LEDGER_PREFIX = "did:indy:sovrin:builder"
  val SUPPORTED_LEDGER_PREFIXES = List(INDY_LEDGER_PREFIX)

  val ENDORSEMENT_RESULT_SUCCESS_CODE = "ENDT-RES-0001"
}
