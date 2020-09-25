package com.evernym.verity.protocol.protocols.deaddrop

import com.evernym.verity.Signature
import com.evernym.verity.protocol.engine.VerKey

case class DeadDropData(recoveryVerKey: VerKey, address: String, locator: String, locatorSignature: Signature, data: Array[Byte]) {

  def buildDeadDropPayload: DeadDropPayload = DeadDropPayload(address, data)

  def buildGetData(): GetData = GetData(recoveryVerKey: VerKey, address: String, locator: String, locatorSignature: Signature)
}
