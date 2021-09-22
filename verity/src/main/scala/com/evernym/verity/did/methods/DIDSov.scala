package com.evernym.verity.did.methods

import com.evernym.verity.did.exception.{InvalidDidSovFormatException, SubNameSpacesUnsupportedException}

class DIDSov(did: String) extends DIDMethod with SelfValidated {
  override val method: String = "sov"
  val splitted: Array[String] = did.split(":")

  override val identifier: String = splitted.length match {
    case x if x > 3 => throw new SubNameSpacesUnsupportedException(did) // TODO: implement support for sub namespaces
    case 3 if splitted(1) == "sov" && splitted(2).matches("^[1-9A-HJ-NP-Za-km-z]{21,22}$") => splitted(2)
    case _ => throw new InvalidDidSovFormatException(did)
  }

}
