package com.evernym.verity.protocol.engine.asyncapi.ledger

import com.evernym.verity.did.DidStr
import com.evernym.verity.util2.Base58Encoded
import org.json.JSONObject


object IndyLedgerUtil {

  def buildIndyRequest(txnJson: Array[Byte],
                       signatures: Map[DidStr, Base58Encoded]): String = {

    val jsonObj = new JSONObject(new String(txnJson))
    val signatureObj = new JSONObject()
    signatures.foreach { case (k, v) =>
      signatureObj.put(k, v)
    }
    jsonObj.put("signatures", signatureObj)
    jsonObj.toString
  }
}
