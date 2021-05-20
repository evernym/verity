package com.evernym.verity.DID

import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.util.Base58Util
import Array._

class DIDKey(val publicKeyBase58: VerKey) extends DID{
  val verKey: VerKey = publicKeyBase58
  override val method: String = "key"                           // 237 is 0xed, the multicodec for Ed25519 public keys
  override val identifier: String = "z"+Base58Util.encode(concat(Array(237.toByte), Base58Util.decode(publicKeyBase58).getOrElse()))

  def resolveKey(): VerKey = verKey
}
