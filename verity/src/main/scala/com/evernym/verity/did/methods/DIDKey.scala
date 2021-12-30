package com.evernym.verity.did.methods

import com.evernym.verity.did.exception.InvalidDidKeyFormatException
import com.evernym.verity.util.Base58Util

import scala.Array._

class DIDKey(did: String) extends DIDMethod with SelfValidated {
  override val method: String = "key"
  override val identifier: String = did.split(":").length match {
    case 3 => {
      did.split(":")(3)
    }                                     // 237 is 0xed01, is the multicodec for Ed25519 public keys
    case 1 => "z"+Base58Util.encode(concat(Array(237.toByte), Array(1.toByte), Base58Util.decode(did).getOrElse(
      throw new InvalidDidKeyFormatException(did))))
    case _ => throw new InvalidDidKeyFormatException(did)
  }

}