package com.evernym.verity.protocol.engine.asyncapi.endorser

import com.evernym.verity.did.DidStr

import scala.util.Try

trait EndorserAccess {

  def withCurrentEndorser(ledgerPrefix: String)(handler: Try[Option[Endorser]] => Unit): Unit

  def endorseTxn(payload: String, ledgerPrefix: String)(handler: Try[Unit] => Unit): Unit
}

case class Endorser(did: DidStr)
