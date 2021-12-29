package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrParams.TaaConfig
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.vdr.Namespace
import com.typesafe.config.Config

import scala.collection.JavaConverters._

object VDRToolsConfig {
  // todo subject to change
  def apply(config: Config): VDRToolsConfig = {

    val ledgers: List[Ledger] = config.getObjectList("verity.vdrs").asScala.toList.map { vdrObject =>
      val vdrConfigReadHelper = ConfigReadHelper(vdrObject.toConfig)
      vdrConfigReadHelper.getStringReq("type") match {
        case "indy" =>
          IndyLedger(
            vdrConfigReadHelper.getStringListReq("namespaces"),
            vdrConfigReadHelper.getStringReq("genesis-txn-file-location"), // todo read file
            vdrConfigReadHelper.getConfigOption("taa").map { taaConfig =>
              val conf = ConfigReadHelper(taaConfig)
              val text = conf.getStringOption("hash")
              val version = conf.getStringOption("version")
              val taaDigest = conf.getStringOption("taaDigest")
              val accMechType = conf.getStringReq("accMechType")
              val time = conf.getStringReq("time")
              // todo add TAA config validation
              if (text.isDefined && version.isDefined) {
                new TaaConfig(text.get, version.get, accMechType, time)
              }
              else {
                new TaaConfig(taaDigest.get, accMechType, time)
              }
            }
          )
      }
    }
    VDRToolsConfig(ledgers)
  }
}

case class VDRToolsConfig(ledgers: List[Ledger]) {
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

case class IndyLedger(namespaces: List[Namespace], genesisTxnFilePath: String, taaConfig: Option[TaaConfig])
  extends Ledger
