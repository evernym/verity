package com.evernym.verity.vdr.service

import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.vdr.Namespace
import com.typesafe.config.Config

import scala.collection.JavaConverters._

object VDRToolsConfig {

  def apply(config: Config): VDRToolsConfig = {
    val toolsConfigReader = ConfigReadHelper(config.getConfig("verity.vdr-tools"))
    val libLocation = toolsConfigReader.getStringReq("library-dir-location")

    val ledgers: List[Ledger] = config.getObjectList("verity.vdrs").asScala.toList.map { vdrObject =>
      val vdrConfigReadHelper = ConfigReadHelper(vdrObject.toConfig)
      vdrConfigReadHelper.getStringReq("type") match {
        case "indy-ledger" =>
          IndyLedger(
            vdrConfigReadHelper.getStringListReq("namespaces"),
            vdrConfigReadHelper.getStringReq("genesis-txn-file-location"),
            vdrConfigReadHelper.getConfigOption("taa").map { taaConfig =>
              val hash = taaConfig.getString("hash")
              TAAConfig(hash)
            }
          )
      }
    }
    VDRToolsConfig(libLocation, ledgers)
  }
}

case class VDRToolsConfig(libraryDirLocation: String, ledgers: List[Ledger]) {
  def validate(): Unit = {
    if (ledgers.isEmpty) {
      throw new RuntimeException("[VDR] no ledger configs found")
    }
    val allNamespaces = ledgers.flatMap(_.namespaces)
    if (allNamespaces.size != allNamespaces.distinct.size) {
      throw new RuntimeException("[VDR] ledgers can not have shared namespaces")
    }
  }
  validate()
}

sealed trait Ledger {
  def namespaces: List[Namespace]
}
case class IndyLedger(namespaces: List[Namespace], genesisTxnFilePath: String, taaConfig: Option[TAAConfig])
  extends Ledger

case class TAAConfig(hash: String)    //TODO: few more parameters may come here