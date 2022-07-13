package com.evernym.verity.did.methods

import com.evernym.verity.did.exception.InvalidUnqualifiedDIDException

// a did with no prefix. This is not an official did method and is intended only for internal use
class UnqualifiedDID(did: String) extends DIDMethod {
  override val method: String = "unqualified"

  did.split(":").length match {
    case x if x > 1 => throw new InvalidUnqualifiedDIDException(did)
    case _ => //nothing to do
  }

  override val methodIdentifier: MethodIdentifier = new MethodIdentifier {
    override def didStr: String = ""
    override def method: String = ""
    override def methodIdentifier: String = did
  }

  override def toString: String = did
}