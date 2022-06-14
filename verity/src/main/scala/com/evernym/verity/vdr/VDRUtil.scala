package com.evernym.verity.vdr

import com.evernym.verity.did.methods.indy_sovrin.DIDIndySovrin
import com.evernym.verity.did.methods.sov.SovIdentifier
import com.evernym.verity.did.methods.UnqualifiedDID
import com.evernym.verity.did.{DidStr, toDIDMethod}

import scala.util.Try


object VDRUtil {

  val LEGACY_LEDGER_PREFIX = s"$DID_PREFIX:sov"

  def toFqDID(did: String, vdrUnqualifiedLedgerPrefix: LedgerPrefix, vdrLegacyLedgerPrefixMappings: Map[LedgerPrefix, LedgerPrefix]): FqDID = {
    if (did.startsWith(LEGACY_LEDGER_PREFIX)) {
      val aliasLedgerPrefix = vdrLegacyLedgerPrefixMappings(LEGACY_LEDGER_PREFIX)
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
      val issuerDid = splitted(0)
      val schemaSeqNo = splitted(3)
      val credDefName = splitted(4)
      val namespace = extractNamespace(issuerFqDid, vdrUnqualifiedLedgerPrefix)
      s"$DID_PREFIX:$namespace:$issuerDid/anoncreds/v0/CLAIM_DEF/$schemaSeqNo/$credDefName"
    }
  }

  /**
   *
   * @param identifier can be fqDID, fqSchemaId or fqCredDefId
   * @param vdrUnqualifiedLedgerPrefix
   * @return
   */
  def extractNamespace(identifier: Option[String], vdrUnqualifiedLedgerPrefix: Option[LedgerPrefix]): Namespace = {
    Try {
      (identifier, vdrUnqualifiedLedgerPrefix) match {
        case (Some(id), _ ) => extractNamespace(id)
        case (None, Some(namespace)) => namespace
        case _ =>
          throw new RuntimeException(s"could not extract namespace for given identifier: $identifier (vdrUnqualifiedLedgerPrefix: $vdrUnqualifiedLedgerPrefix)")
      }
    }.getOrElse {
      vdrUnqualifiedLedgerPrefix.getOrElse {
        throw new RuntimeException(s"could not extract namespace for given identifier: $identifier (vdrUnqualifiedLedgerPrefix: $vdrUnqualifiedLedgerPrefix)")
      }
    }
  }

  /**
   * extracts unqualified DID from given fully qualified DID
   * @param did
   * @return
   */
  def extractUnqualifiedDidStr(did: FqDID): DidStr = {
    Try {
      toDIDMethod(did) match {
        case _: UnqualifiedDID => throw new RuntimeException(s"unqualified DID found: $did")
        case ni: DIDIndySovrin => ni.methodIdentifier.namespaceIdentifier
        case si: SovIdentifier => si.methodIdentifier
        case other => other.methodIdentifier.toString
      }
    }.getOrElse(did)
  }

  /**
   * extracts namespace from given fully qualified DID
   * @param did
   * @return
   */
  def extractNamespace(did: FqDID): DidStr = {
    toDIDMethod(did) match {
      case _: UnqualifiedDID => throw new RuntimeException(s"unqualified DID found: $did")
      case ni: DIDIndySovrin => ni.methodIdentifier.namespace
      case si: SovIdentifier => si.method
      case other => other.method
    }
  }

}
