package com.evernym.verity.protocol.testkit

import com.evernym.verity.protocol.engine.asyncapi.endorser.{Endorser, EndorserAccess}

import scala.util.Try

object MockableEndorserAccess {
  def apply(): MockableEndorserAccess = new MockableEndorserAccess(Map.empty)

  def apply(endorsers: Map[String, List[Endorser]]): MockableEndorserAccess = new MockableEndorserAccess(endorsers)
}

class MockableEndorserAccess(endorsers: Map[String, List[Endorser]])
  extends EndorserAccess {

  override def withCurrentEndorser(ledger: String)(handler: Try[Option[Endorser]] => Unit): Unit = {
    handler(Try(endorsers.get(ledger).flatMap(_.headOption)))
  }

  override def endorseTxn(payload: String, ledgerPrefix: String)(handler: Try[Unit] => Unit): Unit = {
    handler(Try(()))
  }
}

