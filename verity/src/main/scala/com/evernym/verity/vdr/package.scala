package com.evernym.verity

package object vdr {

  type SignatureSpec = String
  type EndorsementSpec = String

  type LedgerPrefix = String    // for example: "did:indy:sovrin", "did:indy:sovrin:staging" etc
  type Namespace = String       // for example: "indy:sovrin", "indy:sovrin:staging" etc

  type SchemaId = String        //schema identifier (either nonfq or fq)
  type CredDefId = String       //creddef identifier (either nonfq or fq)

  type FqDID = String
  type FqSchemaId = String
  type FqCredDefId = String

  type VdrDid = String
  type VdrSchema = String
  type VdrCredDef = String
  type TxnResult = String
  type TxnSpecificParams = String

  type Ledgers = List[Map[String, Any]]

  val DID_PREFIX = "did"
}
