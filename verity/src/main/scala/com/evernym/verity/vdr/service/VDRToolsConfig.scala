package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrParams.TaaConfig
import com.evernym.verity.config.ConfigConstants.VDR_DEFAULT_NAMESPACE
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.util2.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.util2.Status.VALIDATION_FAILED
import com.evernym.verity.vdr.Namespace
import com.typesafe.config.Config

import scala.io.Source.fromFile


sealed trait Ledger {
  def namespaces: List[Namespace]
}

case class IndyLedger(namespaces: List[Namespace],
                      genesisTxnData: String,
                      taaConfig: Option[TaaConfig]) extends Ledger

case class VDRToolsConfig(defaultNamespace: String, ledgers: List[Ledger]) {
  def validate(): Unit = {
    if (ledgers.isEmpty) {
      throw new RuntimeException("[VDR] no ledger configs found")
    }
    val allNamespaces = ledgers.flatMap(_.namespaces)
    if (allNamespaces.size != allNamespaces.distinct.size) {
      throw new RuntimeException("[VDR] ledgers can not have shared namespaces")
    }
    if (! allNamespaces.contains(defaultNamespace)) {
      throw new RuntimeException(s"[VDR] '$VDR_DEFAULT_NAMESPACE' ($defaultNamespace) is not found in registered ledger's namespaces (${allNamespaces.mkString(", ")})")
    }
  }

  validate()
}

object VDRToolsConfig {
  def loadIndyTAA(vdrIndyTaaConfig: Config): TaaConfig = {
    val config = ConfigReadHelper(vdrIndyTaaConfig)

    val text = config.getStringOption("text")
    val version = config.getStringOption("version")
    val digest = config.getStringOption("digest")

    val mechanism = config.getStringReq("mechanism")
    val time = config.getLongReq("time-of-acceptance")

    // TODO: Move validation to TaaConfig?
    if (text.isDefined && version.isDefined) {
      new TaaConfig(text.get, version.get, mechanism, time)
    } else if (digest.isDefined ) {
      new TaaConfig(digest.get, mechanism, time)
    } else {
      throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Some("either taa digest or text and version should be defined"))
    }
  }

  def loadIndyLedger(vdrIndyLedgerConfig: Config): IndyLedger = {
    val config = ConfigReadHelper(vdrIndyLedgerConfig)

    val namespaces = config.getStringListReq("namespaces")

    try {
      val genesisDataSrc = fromFile(config.getStringReq("genesis-txn-file-location"))
      val genesisData = genesisDataSrc.mkString
      genesisDataSrc.close()

      IndyLedger(
        namespaces,
        genesisData,
        config.getConfigOption("transaction-author-agreement").map(loadIndyTAA)
      )
    } catch {
      case ex: ConfigLoadingFailedException =>
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode,
          Some(s"while loading Indy Ledger for ($namespaces): ${ex.respMsg.getOrElse("")}"))
    }
  }

  def loadLedger(vdrLedgerConfig: Config): Ledger = {
    val config = ConfigReadHelper(vdrLedgerConfig)
    val ledgerType = config.getStringReq("type")
    ledgerType match {
      case "indy" => loadIndyLedger(vdrLedgerConfig)
      case _ => throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"unsupported ledger type: $ledgerType"))
    }
  }

  def load(config: Config): VDRToolsConfig = {
    val defaultNamespace = ConfigReadHelper(config).getStringOption(VDR_DEFAULT_NAMESPACE).getOrElse(
      throw new RuntimeException(s"[VDR] required configuration not found: '$VDR_DEFAULT_NAMESPACE'")
    )
    val ledgersConfig = ConfigReadHelper(config).getObjectListReq("verity.vdr.ledgers").map(_.toConfig)
    VDRToolsConfig(defaultNamespace, ledgersConfig.map(loadLedger).toList)
  }
}
