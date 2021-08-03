package com.evernym.verity.did.methods

import com.evernym.verity.did.VerKey
import com.evernym.verity.did.exception.InvalidDidKeyFormatException
import com.evernym.verity.util.Base58Util

import scala.Array._

class DIDKey(val publicKeyBase58: VerKey) extends DIDMethod{
  val verKey: VerKey = publicKeyBase58
  override val method: String = "key"      // 237 is 0xed, is the multicodec for Ed25519 public keys
  override val identifier: String = "z"+Base58Util.encode(concat(Array(237.toByte), Base58Util.decode(publicKeyBase58).getOrElse(
    throw new InvalidDidKeyFormatException(publicKeyBase58))))


  def resolveKey(): VerKey = verKey
}