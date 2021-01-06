package com.evernym.verity.libindy.wallet.operation_executor

import java.util.concurrent.CompletableFuture

import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util.UtilBase
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyParam, WalletExt}

import scala.language.implicitConversions
import scala.compat.java8.FutureConverters
import scala.concurrent.Future


trait OpExecutorBase extends FutureConverter {

  def getVerKey(did: DID,
                getKeyFromPool: Boolean,
                util: UtilBase,
                ledgerPoolManager: LedgerPoolConnManager)(implicit we: WalletExt): Future[VerKey] = {
    util.getVerKey(did, we, getKeyFromPool, ledgerPoolManager)
  }

  def verKeyFuture(keys: Set[KeyParam], util: UtilBase, ledgerPoolManager: LedgerPoolConnManager)
                          (implicit we: WalletExt): Future[Set[VerKey]] = {
    Future.sequence {
      keys.map { rk =>
        rk.verKeyParam match {
          case Left(vk) => Future(vk)
          case Right(gvk: GetVerKeyByDIDParam) =>
            getVerKey(gvk.did, gvk.getKeyFromPool, util, ledgerPoolManager)
        }
      }
    }
  }
}

trait FutureConverter {
  implicit def convertToScala[T](codeBlock: => CompletableFuture[T]): Future[T] =
    FutureConverters.toScala(codeBlock)
}
