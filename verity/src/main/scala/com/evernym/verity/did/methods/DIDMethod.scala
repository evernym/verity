package com.evernym.verity.did.methods

trait DIDMethod {
  val scheme: String = "did"
  val method: String                            //for example: "sov", "indy" etc
  val methodIdentifier: MethodIdentifier        //for example: "123", "sovrin:123", "sovrin:staging:123" etc

  override def toString: String = s"$scheme:$method:${methodIdentifier.toString}"
}

trait SelfValidated