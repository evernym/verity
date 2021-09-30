package com.evernym.verity.vdr.service

import com.evernym.verity.vdr.Namespace

import scala.concurrent.Future

//A thin wrapper around VDRTools API for production code
class VDRToolsImpl(libDirLocation: String)
  extends VDRTools {

  private val wrapperVDR: Any = null //replace `null` with actual VDR object creation call

  override def registerIndyLedger(namespaces: List[Namespace],
                                  genesisTxnFilePath: String,
                                  taaConfig: Option[TAAConfig]): Future[LedgerRegistered] = {

    //TODO: replace this mock implementation with actual VDR wrapper apis calls once it is available
    Future.successful(LedgerRegistered())
  }
}