package com.evernym.verity.vdr

import com.evernym.verity.vdr.VDRUtil.extractNamespace
import com.evernym.verity.vdr.base.{InMemLedger, TestVDRDidDoc}

import scala.concurrent.Future

case class MockLedgerRegistry(var ledgers: List[InMemLedger] = List.empty) {
  def addLedger(ledger: InMemLedger): Unit = synchronized {
    ledgers :+= ledger
  }

  def cleanup(): Unit = {
    ledgers = List.empty
  }

  //--helper functions

  def addDidDoc(dd: TestVDRDidDoc): Future[Unit] = {
    forLedger(dd.id) { ledger: InMemLedger =>
      ledger.addDidDoc(dd)
    }
  }

  def forLedger[T](fqId: String)(f: InMemLedger => T): Future[T] = {
    try {
      val namespace = extractNamespace(Option(fqId), None)
      val ledger = ledgers.find(_.allSupportedNamespaces.contains(namespace)).getOrElse(
        throw new RuntimeException("ledger not found for the namespace: " + namespace)
      )
      Future.successful(f(ledger))
    } catch {
      case ex: RuntimeException => Future.failed(ex)
    }
  }

  def withLedger[T](ns: Namespace)(f: InMemLedger => T): Future[T] = {
    ledgers.find(_.allSupportedNamespaces.contains(ns)) match {
      case Some(ledger) => Future.successful(f(ledger))
      case None => Future.failed(new RuntimeException("ledger not found for namespace: " + ns))
    }
  }
}
