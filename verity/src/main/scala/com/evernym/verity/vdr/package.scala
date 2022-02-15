package com.evernym.verity

package object vdr {

  type Namespace = String
  type SignatureSpec = String
  type EndorsementSpec = String
  type FQSchemaId = String
  type FQCredDefId = String
  type FQDid = String

  type VdrDid = String
  type VdrSchema = String
  type VdrCredDef = String
  type TxnResult = String
  type TxnSpecificParams = String

  val SCHEME_NAME_DID = "did"
  val SCHEME_NAME_INDY_DID = s"did:sov"
  val SCHEME_NAME_INDY_SCHEMA = s"schema:sov"
  val SCHEME_NAME_INDY_CRED_DEF = s"creddef:sov"
}
