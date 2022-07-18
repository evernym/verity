package com.evernym.verity.vdr

import com.evernym.verity.vdr.VDRUtil.extractNamespace
import com.evernym.verity.vdr.base.{InMemLedger, MockVdrDIDDoc}

import scala.concurrent.Future


case class MockLedgerRegistryBuilder(ledgers: Map[Namespace, InMemLedger] = Map.empty) {

  def withLedger(namespace: Namespace, ledger: InMemLedger): MockLedgerRegistryBuilder= {
    copy(ledgers ++ Map(namespace -> ledger))
  }

  def withLedger(namespaces: List[Namespace], ledger: InMemLedger): MockLedgerRegistryBuilder= {
    copy(ledgers ++ namespaces.map { ns => ns -> ledger})
  }

  def build(): MockLedgerRegistry = {
    val mlr = new MockLedgerRegistry()
    ledgers.foreach { case (ns, ledger) =>
      mlr.addLedger(ns, ledger)
    }
    mlr
  }
}

class MockLedgerRegistry {

  var ledgers: Map[Namespace, InMemLedger] = Map.empty

  def allLedgers: List[InMemLedger] = ledgers.values.toList

  def addLedger(namespace: Namespace, ledger: InMemLedger): Unit = synchronized {
    ledgers += namespace -> ledger
  }

  def cleanup(): Unit = {
    ledgers = Map.empty
  }

  //--helper functions

  def addDidDoc(dd: MockVdrDIDDoc): Future[Unit] = {
    forLedger(dd.id) { ledger: InMemLedger =>
      ledger.addDidDoc(dd)
    }
  }

  def forLedger[T](fqId: String)(f: InMemLedger => T): Future[T] = {
    try {
      val ledger = if (ledgers.size == 1) {
        ledgers.head._2
      } else {
        val namespace = extractNamespace(Option(fqId), None)
        ledgers.getOrElse(namespace, throw new RuntimeException("ledger not found for the namespace: " + namespace))
      }
      Future.successful(f(ledger))
    } catch {
      case ex: RuntimeException => Future.failed(ex)
    }
  }

  def withLedger[T](namespace: Namespace)(f: InMemLedger => T): Future[T] = {
    ledgers.get(namespace) match {
      case Some(ledger) => Future.successful(f(ledger))
      case None => Future.failed(new RuntimeException("ledger not found for namespace: " + namespace))
    }
  }

}
