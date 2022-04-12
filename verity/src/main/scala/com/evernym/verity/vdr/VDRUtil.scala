package com.evernym.verity.vdr

import com.evernym.verity.did.methods.indy_sovrin.DIDIndySovrin
import com.evernym.verity.did.methods.sov.SovIdentifier
import com.evernym.verity.did.methods.UnqualifiedDID
import com.evernym.verity.did.{DidStr, toDIDMethod}

import scala.util.Try


//TODO (VE-3368): Need overall review, its usages and find out if there is better way to code this
object VDRUtil {

  def toFqDID(did: String, vdrDefaultNamespace: Namespace): FqDID = {
    //TODO (VE-3368): how do we know what is the minimum prefix for the given id to know if it is fully qualified or not?
    // below is only taking care of "did" prefix.
    if (did.isEmpty || did.startsWith(DID_PREFIX)) {
      did
    } else {
      s"$DID_PREFIX:$vdrDefaultNamespace:$did"
    }
  }

  def toFqSchemaId(schemaId: String, issuerFqDid: Option[FqDID], vdrDefaultNamespace: Option[Namespace]): FqSchemaId = {
    //TODO (VE-3368): how do we know what is the minimum prefix for the given id to know if it is fully qualified or not?
    if (schemaId.startsWith(INDY_SCHEMA_ID_PREFIX)) {
      schemaId
    } else {
      val namespace = extractNamespace(issuerFqDid, vdrDefaultNamespace)
      val fqSchemaId = toFqDID(schemaId, namespace)
      s"$INDY_SCHEMA_ID_PREFIX:$namespace:$fqSchemaId"
    }
  }

  def toFqCredDefId(credDefId: String, issuerFqDid: Option[FqDID], vdrDefaultNamespace: Option[Namespace]): FqCredDefId = {
    //TODO (VE-3368): how do we know what is the minimum prefix for the given id to know if it is fully qualified or not?
    if (credDefId.startsWith(INDY_CRED_DEF_ID_PREFIX)) {
      credDefId
    } else {
      val namespace = extractNamespace(issuerFqDid, vdrDefaultNamespace)
      val fqCredDefId = toFqDID(credDefId, namespace)
      s"$INDY_CRED_DEF_ID_PREFIX:$namespace:$fqCredDefId"
    }
  }

  /**
   *
   * @param identifier can be fqDID, fqSchemaId or fqCredDefId
   * @param vdrDefaultNamespace
   * @return
   */
  def extractNamespace(identifier: Option[String], vdrDefaultNamespace: Option[Namespace]): Namespace = {
    Try {
      (identifier, vdrDefaultNamespace) match {
        case (Some(id), _ ) => extractNamespace(extractDidStr(id))
        case (None, Some(namespace)) => namespace
        case other => throw new RuntimeException(s"could not extract namespace for given identifier: $identifier (default namespace: $vdrDefaultNamespace)")
      }
    }.getOrElse {
      vdrDefaultNamespace.getOrElse {
        throw new RuntimeException(s"could not extract namespace for given identifier: $identifier (default namespace: $vdrDefaultNamespace)")
      }
    }
  }

  /**
   * extracts DID string from given identifier (which can be did, schema or cred identifier)
   * @param id
   * @return
   */
  private def extractDidStr(id: String): DidStr = {
    if (id.startsWith(DID_PREFIX)) id
    else if (id.startsWith(INDY_SCHEMA_ID_PREFIX))
      id.substring(id.indexOf(DID_PREFIX), id.indexOf(":2:"))
    else if (id.startsWith(INDY_CRED_DEF_ID_PREFIX))
      id.substring(id.indexOf(DID_PREFIX), id.indexOf(":3:"))
    else id
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
