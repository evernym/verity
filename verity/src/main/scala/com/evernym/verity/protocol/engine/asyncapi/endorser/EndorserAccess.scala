package com.evernym.verity.protocol.engine.asyncapi.endorser

import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.LedgerPrefix

import scala.util.Try

trait EndorserAccess {

  def withCurrentEndorser(ledgerPrefix: LedgerPrefix)(handler: Try[Option[Endorser]] => Unit): Unit

  def endorseTxn(payload: String, ledgerPrefix: LedgerPrefix)(handler: Try[Unit] => Unit): Unit
}

case class Endorser(did: DidStr)
