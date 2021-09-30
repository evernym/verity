package com.evernym.verity.vdr.service

import com.evernym.verity.vdr.Namespace

import scala.concurrent.Future

trait VDRTools {
  def registerIndyLedger(namespaces: List[Namespace],
                         genesisTxnFilePath: String,
                         taaConfig: Option[TAAConfig]): Future[LedgerRegistered]
}

case class LedgerRegistered()


