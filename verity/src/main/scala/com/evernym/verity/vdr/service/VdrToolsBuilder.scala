package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrParams.TaaConfig
import com.evernym.verity.config.AppConfig

import scala.concurrent.{ExecutionContext, Future}

trait VdrToolsBuilder {
  def registerIndyLedger(namespaceList: List[String],
                         genesisTxnData: String,
                         taaConfig: Option[TaaConfig]): Future[Unit]

  def registerCheqdLedger(namespaceList: List[String],
                          chainId: String,
                          nodeAddrsList: String): Future[Unit]

  def build(): VdrTools
}