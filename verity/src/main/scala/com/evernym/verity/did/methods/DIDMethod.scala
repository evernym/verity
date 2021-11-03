package com.evernym.verity.did.methods

trait DIDMethod {
  val scheme: String = "did"
  val method: String
  val identifier: String

  override def toString: String = s"$scheme:$method:$identifier"
}

trait SelfValidated