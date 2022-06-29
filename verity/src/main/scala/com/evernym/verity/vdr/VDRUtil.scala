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
      val issuerDid = splitted(0)
      val schemaSeqNo = splitted(3)
      val credDefName = splitted(4)
      val namespace = extractNamespace(issuerFqDid, vdrUnqualifiedLedgerPrefix)
      s"$DID_PREFIX:$namespace:$issuerDid/anoncreds/v0/CLAIM_DEF/$schemaSeqNo/$credDefName"
    }
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
   * @return ledger prefix (for example: "did:indy:sovrin", "did:indy:sovrin:stage" etc)
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
   * @return namespace (for example: "indy:sovrin", "indy:sovrin:stage")
   */
  private def extractNamespaceFromDIDStr(fqDID: FqDID): Namespace = {
    toDIDMethod(fqDID) match {
      case _: UnqualifiedDID => throw new RuntimeException(s"unqualified DID found: $fqDID")
      case ni: DIDIndySovrin => ni.methodIdentifier.namespace
    }
  }

  /**
   * extracts namespace from given ledger prefix
   * @param ledgerPrefix for example ("did:indy:sovrin", "did:indy:sovrin:stage" etc)
   * @return namespace (for example: "indy:sovrin", "indy:sovrin:stage")
   */
  private def extractNamespaceFromLedgerPrefix(ledgerPrefix: LedgerPrefix): Namespace = {
    ledgerPrefix.split(":").tail.mkString(":")   //removes the scheme (for example: `did:`)
  }

}
