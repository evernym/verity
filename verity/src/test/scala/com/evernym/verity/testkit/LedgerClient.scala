package com.evernym.verity.testkit

import java.util.UUID

import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerPoolConnManager, TransactionAuthorAgreement}
import com.evernym.verity.libindy.{IndyLedgerPoolConnManager, LibIndyCommon}
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.testkit.util.LedgerUtil

trait LedgerClient extends LibIndyCommon {
  import LedgerClient._

  def createLedgerUtil(configOpt: Option[AppConfig]=None,
                       submitterDID: Option[DID]=None,
                       submitterKeySeed: Option[String]=None,
                       submitterRole: String = "STEWARD",
                       taa: Option[TransactionAuthorAgreement]=None): LedgerUtil = {
    val confToUse = configOpt.getOrElse{appConfig}
    buildLedgerUtil(confToUse, submitterDID, submitterKeySeed, submitterRole, taa)
  }
}

object LedgerClient {
  def buildLedgerUtil(config: AppConfig,
                      submitterDID: Option[DID]=None,
                      submitterKeySeed: Option[String]=None,
                      submitterRole: String = "STEWARD",
                      taa: Option[TransactionAuthorAgreement]=None,
                      poolConfigName: Option[String] = Some(UUID.randomUUID().toString)): LedgerUtil = {
    lazy val poolConnManager: LedgerPoolConnManager = {
      val pc = new IndyLedgerPoolConnManager(config, poolConfigName)
      pc.open()
      pc
    }
    (submitterDID, submitterKeySeed, submitterRole) match {
      case (Some(tDID), Some(tSeed), role)  => new LedgerUtil(config, poolConnManager, tDID, tSeed, role, taa)
      case _                          => new LedgerUtil(config, poolConnManager, taa = taa)
    }
  }
}
