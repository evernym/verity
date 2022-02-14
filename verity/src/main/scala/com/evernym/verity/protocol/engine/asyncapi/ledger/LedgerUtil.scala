package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.did.DidStr
import com.evernym.verity.util2.Base64Encoded
import org.json.JSONObject

object LedgerUtil {

  def toFQId(id: String, vdrDefaultNamespace: String): String = {
    if (id.startsWith("did:")) {
      id
    } else {
      s"did:$vdrDefaultNamespace:$id"
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

  //TODO: confirm this
  def isIndyNamespace(namespace: String): Boolean = {
    namespace.startsWith("indy:")
  }
}
