package com.evernym.verity.vdr

import com.evernym.verity.did.methods.indy_sovrin.DIDIndySovrin
import com.evernym.verity.did.methods.UnqualifiedDID
import com.evernym.verity.did.{DidStr, toDIDMethod}

import scala.util.{Failure, Success, Try}


object VDRUtil {

  val LEGACY_LEDGER_PREFIX = s"$DID_PREFIX:sov"

  def toFqDID(did: String,
              vdrUnqualifiedLedgerPrefix: LedgerPrefix,
              vdrLedgerPrefixMappings: Map[LedgerPrefix, LedgerPrefix]): FqDID = {
    if (did.startsWith(LEGACY_LEDGER_PREFIX)) {
      val aliasLedgerPrefix = vdrLedgerPrefixMappings(LEGACY_LEDGER_PREFIX)
      did.replace(LEGACY_LEDGER_PREFIX, aliasLedgerPrefix)
    } else if (did.nonEmpty && ! did.startsWith(s"$DID_PREFIX:")) {
      s"$vdrUnqualifiedLedgerPrefix:$did"
    } else {
      did   // it must be fully qualified already
    }
  }

  def toFqSchemaId_v0(schemaId: String,
                      issuerFqDid: Option[FqDID],
                      vdrUnqualifiedLedgerPrefix: Option[LedgerPrefix]): FqSchemaId = {
    if (schemaId.startsWith(DID_PREFIX)) {
      schemaId
    } else {
      val splitted = schemaId.split(":")
      val issuerDid = splitted(0)
      val schemaName = splitted(2)
      val schemaVersion = splitted(3)
      val namespace = extractNamespace(issuerFqDid, vdrUnqualifiedLedgerPrefix)
      s"$DID_PREFIX:$namespace:$issuerDid/anoncreds/v0/SCHEMA/$schemaName/$schemaVersion"
    }
  }

  def toFqCredDefId_v0(credDefId: String,
                       issuerFqDid: Option[FqDID],
                       vdrUnqualifiedLedgerPrefix: Option[LedgerPrefix]): FqCredDefId = {
    if (credDefId.startsWith(DID_PREFIX)) {
      credDefId
    } else {
      val splitted = credDefId.split(":")
      if (splitted.length == 5) {
        //NcYxiDXkpYi6ov5FcYDi1e:3:CL:1234:tag
        val namespace = extractNamespace(issuerFqDid, vdrUnqualifiedLedgerPrefix)
        val issuerDid = splitted(0)
        val schemaId = splitted(3)    //the schema txn sequence number
        val credDefName = splitted(4)
        s"$DID_PREFIX:$namespace:$issuerDid/anoncreds/v0/CLAIM_DEF/$schemaId/$credDefName"
      } else {
        //NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:tag
        val namespace = extractNamespace(issuerFqDid, vdrUnqualifiedLedgerPrefix)
        val issuerDid = splitted(0)
        val schemaName = splitted(5)
        val schemaVer = splitted(6)
        val schemaId = s"$DID_PREFIX:$namespace:$issuerDid/anoncreds/v0/SCHEMA/$schemaName/$schemaVer"
        val credDefName = splitted(7)
        s"$DID_PREFIX:$namespace:$issuerDid/anoncreds/v0/CLAIM_DEF/$schemaId/$credDefName"
      }
    }
  }

  def toLegacyNonFqSchemaId(schemaId: FqSchemaId): String = {
    Try {
      val splitted = schemaId.split("/", 2)
      val issuer = splitted(0)
      val claim = splitted(1)

      val issuerParts = issuer.split(":")
      val schemaParts = claim.split("/")

      s"${issuerParts.last}:2:${schemaParts(3)}:${schemaParts.last}"
    }.getOrElse(schemaId)
  }

  def toLegacyNonFqCredDefId(credDefId: FqCredDefId): String = {
    Try {
      val splitted = credDefId.split("/", 2)
      val issuer = splitted(0)
      val claim = splitted(1)

      val issuerParts = issuer.split(":")
      val claimParts = claim.split("/")
      if (claimParts.length == 5) {
        //claim: anoncreds/v0/CLAIM_DEF/1234/Tag1
        s"${issuerParts.last}:3:CL:${claimParts(3)}:${claimParts.last}"
      } else {
        //claim: anoncreds/v0/CLAIM_DEF/did:indy:sovrin:NcYxiDXkpYi6ov5FcYDi1e/anoncreds/v0/SCHEMA/gvt/1.0/Tag1
        val schemaName = claimParts(7)
        val schemaVer = claimParts(8)
        val schemaId = s"${issuerParts.last}:2:$schemaName:$schemaVer"
        s"${issuerParts.last}:3:CL:$schemaId:${claimParts.last}"
      }
    }.getOrElse(credDefId)
  }

  def toLegacyNonFqDid(did: FqDID): String = {
    Try {
      val splitted = did.split(":")

      s"${splitted.last}"
    }.getOrElse(did)
  }


  /**
   *
   * @param fqDID fqDID
   * @param vdrUnqualifiedLedgerPrefix
   * @return
   */
  def extractNamespace(fqDID: Option[FqDID],
                       vdrUnqualifiedLedgerPrefix: Option[LedgerPrefix]): Namespace = {
    Try {
      (fqDID, vdrUnqualifiedLedgerPrefix) match {
        case (Some(id), _ ) =>
          extractNamespaceFromDIDStr(id)
        case (None, Some(ledgerPrefix)) =>
          extractNamespaceFromLedgerPrefix(ledgerPrefix)
        case _ =>
          throw new RuntimeException(s"could not extract namespace for given identifier: $fqDID (vdrUnqualifiedLedgerPrefix: $vdrUnqualifiedLedgerPrefix)")
      }
    }.getOrElse {
      vdrUnqualifiedLedgerPrefix
        .map(extractNamespaceFromLedgerPrefix)
        .getOrElse {
          throw new RuntimeException(s"could not extract namespace for given identifier: $fqDID (vdrUnqualifiedLedgerPrefix: $vdrUnqualifiedLedgerPrefix)")
        }
    }
  }

  /**
   * extracts ledger prefix from given fully qualified DID
   * @param fqDID (for example: "did:indy:sovrin:123")
   * @return ledger prefix (for example: "did:indy:sovrin", "did:indy:sovrin:staging" etc)
   */
  def extractLedgerPrefix(fqDID: FqDID): LedgerPrefix = {
    Try {
      toDIDMethod(fqDID) match {
        case ni: DIDIndySovrin => s"${ni.scheme}:${ni.methodIdentifier.namespace}"
        case other => throw new RuntimeException(s"unsupported did method: '${other.method}'")
      }
    } match {
      case Success(value) => value
      case Failure(exception) =>
        throw new RuntimeException(s"error while extracting ledger prefix from fqDID: '$fqDID' (${exception.getMessage})")
    }
  }

  /**
   * extracts unqualified DID from given fully qualified DID
   * @param did (for example: "did:indy:sovrin:123", "123")
   * @return unqualified DID str (for example: "123")
   */
  def extractUnqualifiedDidStr(did: DidStr): DidStr = {
    Try {
      toDIDMethod(did) match {
        case _: UnqualifiedDID => did
        case ni: DIDIndySovrin => ni.methodIdentifier.namespaceIdentifier
      }
    }.getOrElse(did)
  }

  /**
   * extracts namespace from given fully qualified DID
   * @param fqDID (for example: "did:indy:sovrin:123")
   * @return namespace (for example: "indy:sovrin", "indy:sovrin:staging")
   */
  private def extractNamespaceFromDIDStr(fqDID: FqDID): Namespace = {
    toDIDMethod(fqDID) match {
      case _: UnqualifiedDID => throw new RuntimeException(s"unqualified DID found: $fqDID")
      case ni: DIDIndySovrin => ni.methodIdentifier.namespace
    }
  }

  /**
   * extracts namespace from given ledger prefix
   * @param ledgerPrefix for example ("did:indy:sovrin", "did:indy:sovrin:staging" etc)
   * @return namespace (for example: "indy:sovrin", "indy:sovrin:staging")
   */
  private def extractNamespaceFromLedgerPrefix(ledgerPrefix: LedgerPrefix): Namespace = {
    ledgerPrefix.split(":").tail.mkString(":")   //removes the scheme (for example: `did:`)
  }

}