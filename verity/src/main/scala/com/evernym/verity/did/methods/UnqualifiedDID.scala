package com.evernym.verity.did.methods

// a did with no prefix. This is not an official did method and is intended only for internal use
class UnqualifiedDID(did: String) extends DIDMethod {
  override val method: String = "unqualified"
  override val identifier: String = did

  override def toString = {
    identifier
  }
}