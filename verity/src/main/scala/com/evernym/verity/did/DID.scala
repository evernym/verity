package com.evernym.verity.did

trait DID {
  val scheme: String = "did"
  val method: String
  val identifier: String

  override def toString: String = s"${scheme}:${method}:${identifier}"
}
