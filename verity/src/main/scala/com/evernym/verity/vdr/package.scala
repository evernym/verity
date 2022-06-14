package com.evernym.verity

package object vdr {

  type SignatureSpec = String
  type EndorsementSpec = String

  type LedgerPrefix = String
  type Namespace = String       // for example: "indy:sovrin", "indy:sovrin:stage" etc
  type FqDID = String
  type FqSchemaId = String
  type FqCredDefId = String

  type VdrDid = String
  type VdrSchema = String
  type VdrCredDef = String
  type TxnResult = String
  type TxnSpecificParams = String

  val DID_PREFIX = "did"
}
