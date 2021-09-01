package com.evernym.verity.testkit

import com.evernym.verity.actor.HasAppConfig

import java.util.UUID
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.TransactionAuthorAgreement
import com.evernym.verity.did.DidStr
import com.evernym.verity.testkit.util.LedgerUtil

import scala.concurrent.ExecutionContext

trait LedgerClient extends HasAppConfig {
  import LedgerClient._

  def createLedgerUtil(ec: ExecutionContext,
                       wec: ExecutionContext,
                       configOpt: Option[AppConfig]=None,
                       submitterDID: Option[DidStr]=None,
                       submitterKeySeed: Option[String]=None,
                       submitterRole: String = "STEWARD",
                       taa: Option[TransactionAuthorAgreement]=None): LedgerUtil = {
    val confToUse = configOpt.getOrElse(appConfig)
    buildLedgerUtil(confToUse, ec, wec, submitterDID, submitterKeySeed, submitterRole, taa)
  }
}

object LedgerClient {
  def buildLedgerUtil(config: AppConfig,
                      ec: ExecutionContext,
                      wec: ExecutionContext,
                      submitterDID: Option[DidStr]=None,
                      submitterKeySeed: Option[String]=None,
                      submitterRole: String = "STEWARD",
                      taa: Option[TransactionAuthorAgreement]=None,
                      genesisTxnPath: Option[String] = None): LedgerUtil = {

    val poolConfigName: Option[String] = Some(UUID.randomUUID().toString)
    (submitterDID, submitterKeySeed, submitterRole) match {
      case (Some(tDID), Some(tSeed), role)  => new LedgerUtil(config, poolConfigName, ec, wec, tDID, tSeed, role, taa, genesisTxnPath = genesisTxnPath)
      case _                                => new LedgerUtil(config, poolConfigName, ec, wec, taa = taa, genesisTxnPath = genesisTxnPath)
    }
  }
}
