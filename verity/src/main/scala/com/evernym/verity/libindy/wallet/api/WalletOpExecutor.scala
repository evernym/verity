package com.evernym.verity.libindy.wallet.api

import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.util.UtilBase
import com.evernym.verity.vault.service._
import com.evernym.verity.vault.WalletExt
import com.typesafe.scalalogging.Logger


import scala.concurrent.{Await, Future}

object WalletOpExecutor {

  val logger: Logger = getLoggerByName(WalletOpExecutor.getClass.getSimpleName)

//  def getVerKeyFromDetail(gvk: GetVerKeyFut, util: UtilBase, ledgerPoolManager: LedgerPoolConnManager)
//                         (implicit we: WalletExt): Future[VerKey] = {
//    gvk.verKeyDetail.fold (
//      l => Future.successful(l),
//      r => {
//        getVerKeyAsync(r.did, r.getKeyFromPool, util, ledgerPoolManager)
//      }
//    )
//  }

  def getVerKey(did: DID,
                getKeyFromPool: Boolean,
                util: UtilBase,
                ledgerPoolManager: LedgerPoolConnManager)(implicit we: WalletExt): Future[VerKey] = {
    util.getVerKey(did, we, getKeyFromPool, ledgerPoolManager)
  }

}
