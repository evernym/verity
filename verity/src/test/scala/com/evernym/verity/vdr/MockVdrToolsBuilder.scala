package com.evernym.verity.vdr

import com.evernym.vdrtools.vdr.VdrParams
import com.evernym.verity.vdr.service.{VdrTools, VdrToolsBuilder}

import scala.concurrent.{ExecutionContext, Future}


class MockVdrToolsBuilder(ledgerRegistry: MockLedgerRegistry,
                          givenVdrTools: Option[VdrTools]=None)(implicit ec: ExecutionContext)
  extends VdrToolsBuilder {

  override def registerIndyLedger(namespaceList: List[String],
                                  genesisTxnData: String,
                                  taaConfig: Option[VdrParams.TaaConfig]): Future[Unit] = {
    val ledger = MockIndyLedger(genesisTxnData, taaConfig)
    namespaceList.foreach(ns => ledgerRegistry.addLedger(ns, ledger))
    Future.successful(())
  }

  override def registerCheqdLedger(namespaceList: List[String], chainId: String, nodeAddrsList: String): Future[Unit] = {
    Future.failed(new Exception("Not implemented yet"))
  }

  override def build(): VdrTools = {
    givenVdrTools.getOrElse(new MockVdrTools(ledgerRegistry))
  }
}
