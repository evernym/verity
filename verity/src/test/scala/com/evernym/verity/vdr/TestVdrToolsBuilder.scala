package com.evernym.verity.vdr

import com.evernym.vdrtools.vdr.VdrParams
import com.evernym.verity.vdr.service.{VdrTools, VdrToolsBuilder}

import scala.concurrent.{ExecutionContext, Future}

class TestVdrToolsBuilder(implicit ec: ExecutionContext) extends VdrToolsBuilder {
  private var ledgerRegistry: TestLedgerRegistry = TestLedgerRegistry(List.empty)

  override def registerIndyLedger(namespaceList: List[String], genesisTxnData: String, taaConfig: Option[VdrParams.TaaConfig]): Future[Unit] = {
    ledgerRegistry = ledgerRegistry.withNewLedger(TestIndyLedger(namespaceList, genesisTxnData, taaConfig))
    Future.successful()
  }

  override def registerCheqdLedger(namespaceList: List[String], chainId: String, nodeAddrsList: String): Future[Unit] = {
    Future.failed(new Exception("Not implemented yet"))
  }

  override def build(): VdrTools = {
    new TestVdrTools(ledgerRegistry)
  }
}
