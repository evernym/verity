package com.evernym.verity.DID

trait DID {
  val scheme: String = "did"
  val method: String
  val identifier: String

  override def toString: String = {
    return scheme+":"+method+":"+identifier
  }
}
