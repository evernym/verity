package com.evernym.verity.protocol.engine.asyncapi.endorser

import com.evernym.verity.did.{DidStr, VerKeyStr}

import scala.util.Try

trait EndorserAccess {
  def withCurrentEndorser(ledger: String)(handler: Try[Option[Endorser]] => Unit): Unit
}

case class Endorser(did: DidStr, verKey: VerKeyStr)
