package com.evernym.verity.protocol.protocols.deaddrop

import com.evernym.verity.did.VerKey
import com.evernym.verity.util2.Signature
import com.evernym.verity.did.VerKey


case class DeadDropData(recoveryVerKey: VerKey, address: String, locator: String, locatorSignature: Signature, data: Array[Byte]) {

  def buildDeadDropPayload: DeadDropPayload = DeadDropPayload(address, data)

  def buildGetData(): GetData = GetData(recoveryVerKey: VerKey, address: String, locator: String, locatorSignature: Signature)
}
