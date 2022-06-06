package com.evernym.verity.did.methods.indy_sovrin

import com.evernym.verity.did.exception.{InvalidDidIndySovrinFormatException, SubNameSpacesUnsupportedException}
import com.evernym.verity.did.methods.{DIDMethod, SelfValidated}

/**
 *
 * @param did "did:indy:sovrin:123", "did:indy:sovrin:stage:123", "did:indy:sovrin:builder:123"
 */

class DIDIndySovrin(did: String) extends DIDMethod with SelfValidated {
  override val method: String = "indy"
  private val namespace: String = "sovrin"

  private val splitted: Array[String] = did.split(":")

  override val methodIdentifier: IndySovrinIdentifier = splitted.length match {
    case x if x > 5 => throw new SubNameSpacesUnsupportedException(did) // TODO: implement support for sub namespaces
    case 5 if splitted(1) == method && splitted(2) == namespace => IndySovrinIdentifier(did, method, splitted.takeRight(3).mkString(":"))
    case 4 if splitted(1) == method && splitted(2) == namespace => IndySovrinIdentifier(did, method, splitted.takeRight(2).mkString(":"))
    case _ => throw new InvalidDidIndySovrinFormatException(did)
  }

}
