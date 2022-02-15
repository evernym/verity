package com.evernym.verity.did.methods

trait DIDMethod {
  val scheme: String = "did"
  val method: String
  val methodIdentifier: MethodIdentifier

  override def toString: String = s"$scheme:$method:${methodIdentifier.toString}"
}

trait SelfValidated