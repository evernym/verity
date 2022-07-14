package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrParams.TaaConfig
import com.evernym.verity.config.ConfigConstants.{VDR_LEDGER_PREFIX_MAPPINGS, VDR_UNQUALIFIED_LEDGER_PREFIX}
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.TAAUtil
import com.evernym.verity.util2.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.util2.Status.VALIDATION_FAILED
import com.evernym.verity.vdr.{DID_PREFIX, LedgerPrefix, Namespace}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import scala.io.Source.fromFile
import scala.util.Try


sealed trait Ledger {
  def namespaces: List[Namespace]
}

case class IndyLedger(namespaces: List[Namespace],
                      genesisTxnData: String,
                      taaConfig: Option[TaaConfig]) extends Ledger

case class VDRToolsConfig(unqualifiedLedgerPrefix: LedgerPrefix,
                          ledgerPrefixMapping: Map[LedgerPrefix, LedgerPrefix],
                          ledgers: List[Ledger]) {
  def validate(): Unit = {
    if (ledgers.isEmpty) {
      throw new RuntimeException("[VDR] no ledger configs found")
    }
    val allNamespaces = ledgers.flatMap(_.namespaces)
    if (allNamespaces.size != allNamespaces.distinct.size) {
      throw new RuntimeException("[VDR] ledgers can not have shared namespaces")
    }
    if (! unqualifiedLedgerPrefix.startsWith(s"$DID_PREFIX:")) {
      throw new RuntimeException(s"[VDR] '$VDR_UNQUALIFIED_LEDGER_PREFIX' not supported")
    }
    val unqualifiedLedgerNamespace = unqualifiedLedgerPrefix.replace(s"$DID_PREFIX:", "")
    if (! allNamespaces.contains(unqualifiedLedgerNamespace)) {
      throw new RuntimeException(s"[VDR] '$VDR_UNQUALIFIED_LEDGER_PREFIX' namespace ($unqualifiedLedgerNamespace) is not found in " +
        s"registered ledger's namespaces (${allNamespaces.mkString(", ")})")
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

    //NOTE: didn't find a proper way to set TAA environment variable as long value (instead of string) in tests,
    // thats why added few Try blocks to check for below mentioned patterns:
    // either `time-of-acceptance` can be defined as `string` in date format (for ex: 2022-04-08) or
    // epoch seconds or it can be defined as `long` equivalent to epoch seconds
    val timeOfAcceptance =
    Try(TAAUtil.taaAcceptanceEpochDateTime(config.getStringReq("time-of-acceptance")))
      .getOrElse(
        Try(config.getStringReq("time-of-acceptance").toLong)
          .getOrElse(config.getLongReq("time-of-acceptance")))

    // TODO: Move validation to TaaConfig?
    if (text.isDefined && version.isDefined) {
      new TaaConfig(text.get, version.get, mechanism, timeOfAcceptance)
    } else if (digest.isDefined ) {
      new TaaConfig(digest.get, mechanism, timeOfAcceptance)
    } else {
      throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Some("either taa 'digest' or 'text and version' should be defined"))
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
    val confReadHelper = ConfigReadHelper(config)
    val unqualifiedLedgerPrefix =
      confReadHelper
        .getStringOption(VDR_UNQUALIFIED_LEDGER_PREFIX)
        .getOrElse(throw new RuntimeException(s"[VDR] required configuration not found: '$VDR_UNQUALIFIED_LEDGER_PREFIX'"))
    val ledgerPrefixMappings =
      confReadHelper
        .getMap(VDR_LEDGER_PREFIX_MAPPINGS)
    val ledgersConfig = confReadHelper.getObjectListReq("verity.vdr.ledgers").map(_.toConfig)
    logger.info(s"vdr tools config => unqualifiedLedgerPrefix: $unqualifiedLedgerPrefix, ledgerPrefixMappings: $ledgerPrefixMappings")
    VDRToolsConfig(unqualifiedLedgerPrefix, ledgerPrefixMappings, ledgersConfig.map(loadLedger).toList)
  }

  val logger: Logger = getLoggerByClass(getClass)
}

