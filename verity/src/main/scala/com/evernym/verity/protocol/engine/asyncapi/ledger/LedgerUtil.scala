package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.did.DidStr
import com.evernym.verity.util2.Base64Encoded
import com.evernym.verity.vdr.{SCHEME_NAME_DID, SCHEME_NAME_INDY_CRED_DEF, SCHEME_NAME_INDY_SCHEMA}
import org.json.JSONObject

object LedgerUtil {

  def toFQId(id: String, vdrDefaultNamespace: String): String = {
    //TODO: how do we know what is the minimum prefix for the given id to know if it is fully qualified or not?
    // below is only taking care of "did" prefix.
    if (id.isEmpty || id.startsWith(SCHEME_NAME_DID)) {
      id
    } else {
      s"$SCHEME_NAME_DID:$vdrDefaultNamespace:$id"
    }
  }

  def toFQSchemaId(id: String, vdrDefaultNamespace: String): String = {
    //TODO: how do we know what is the minimum prefix for the given id to know if it is fully qualified or not?
    if (id.startsWith(SCHEME_NAME_INDY_SCHEMA)) {
      id
    } else {
      val fqId = toFQId(id, vdrDefaultNamespace)
      s"$SCHEME_NAME_INDY_SCHEMA:$fqId"
    }
  }

  def toFQCredDefId(id: String, vdrDefaultNamespace: String): String = {
    //TODO: how do we know what is the minimum prefix for the given id to know if it is fully qualified or not?
    if (id.startsWith(SCHEME_NAME_INDY_CRED_DEF)) {
      id
    } else {
      val fqId = toFQId(id, vdrDefaultNamespace)
      s"$SCHEME_NAME_INDY_CRED_DEF:$fqId"
    }
  }

  def buildIndyRequest(txnJson: Array[Byte],
                       signatures: Map[DidStr, Base64Encoded]): String = {

    val jsonObj = new JSONObject(new String(txnJson))
    val signatureObj = new JSONObject()
    signatures.foreach { case (k, v) =>
      signatureObj.put(k, v)
    }
    jsonObj.put("signatures", signatureObj)
    jsonObj.toString
  }

  //TODO: finalize this
  def isIndyNamespace(namespace: String): Boolean = {
    namespace.startsWith("indy:sovrin") || namespace.startsWith("sov")
  }
}
