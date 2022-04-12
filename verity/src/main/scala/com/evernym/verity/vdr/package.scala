package com.evernym.verity

package object vdr {

  type SignatureSpec = String
  type EndorsementSpec = String

  type Namespace = String       // for example: "sov", "indy:sovrin", "indy:sovrin:staging" etc
  type FqDID = String
  type FqSchemaId = String
  type FqCredDefId = String

  type VdrDid = String
  type VdrSchema = String
  type VdrCredDef = String
  type TxnResult = String
  type TxnSpecificParams = String

  val DID_PREFIX = "did"
  val DID_METHOD_INDY = "indy"
  val INDY_SCHEMA_ID_PREFIX = "schema"
  val INDY_CRED_DEF_ID_PREFIX = "creddef"
}
