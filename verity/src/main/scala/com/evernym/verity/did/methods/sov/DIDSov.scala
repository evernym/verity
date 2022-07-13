package com.evernym.verity.did.methods.sov

import com.evernym.verity.did.exception.{InvalidDidSovFormatException, SubNameSpacesUnsupportedException}
import com.evernym.verity.did.methods.{DIDMethod, SelfValidated}

/**
 *
 * @param did "did:sov:123"
 */
class DIDSov(did: String) extends DIDMethod with SelfValidated {
  override val method: String = "sov"

  private val splitted: Array[String] = did.split(":")

  override val methodIdentifier: SovIdentifier = splitted.length match {
    case x if x > 3 => throw new SubNameSpacesUnsupportedException(did) // TODO: implement support for sub namespaces
    case 3 if splitted(1) == method => SovIdentifier(did, method, splitted.last)
    case _ => throw new InvalidDidSovFormatException(did)
  }

}
