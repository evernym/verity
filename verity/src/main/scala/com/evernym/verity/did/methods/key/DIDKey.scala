package com.evernym.verity.did.methods.key

import com.evernym.verity.did.exception.InvalidDidKeyFormatException
import com.evernym.verity.did.methods.{DIDMethod, SelfValidated}
import com.evernym.verity.util.Base58Util

import scala.Array._

/**
 *
 * @param did "123", "did:indy:key:123" etc
 */
class DIDKey(did: String)
  extends DIDMethod
    with SelfValidated {

  override val method: String = "key"

  private val splitted: Array[String] = did.split(":")

  override val methodIdentifier: KeyIdentifier = did.split(":").length match {
    case 3 if splitted(1) == method =>  KeyIdentifier(did, method, splitted.last)

    case 1 => KeyIdentifier(
      did,
      method,
      "z" +                          //z = base58 ?
      Base58Util.encode(
        concat(
          Array(237.toByte),        // 237 is 0xed01, is the multicodec for Ed25519 public keys
          Array(1.toByte),          // ???
          Base58Util.decode(did).getOrElse(throw new InvalidDidKeyFormatException(did))
        )
      )
    )
    case _ => throw new InvalidDidKeyFormatException(did)
  }

}