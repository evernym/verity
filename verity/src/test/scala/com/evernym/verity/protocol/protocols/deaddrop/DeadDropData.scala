package com.evernym.verity.protocol.protocols.deaddrop

import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.util2.Signature
import com.evernym.verity.did.VerKeyStr


case class DeadDropData(recoveryVerKey: VerKeyStr, address: String, locator: String, locatorSignature: Signature, data: Array[Byte]) {

  def buildDeadDropPayload: DeadDropPayload = DeadDropPayload(address, data)

  def buildGetData(): GetData = GetData(recoveryVerKey: VerKeyStr, address: String, locator: String, locatorSignature: Signature)
}
