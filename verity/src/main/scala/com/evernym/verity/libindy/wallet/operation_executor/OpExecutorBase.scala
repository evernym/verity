package com.evernym.verity.libindy.wallet.operation_executor

import java.util.concurrent.CompletableFuture

import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.ExecutionContextProvider.walletFutureExecutionContext
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyParam, WalletExt}

import scala.language.implicitConversions
import scala.compat.java8.FutureConverters
import scala.concurrent.Future

trait OpExecutorBase extends FutureConverter {

  def verKeyFuture(keys: Set[KeyParam], ledgerPoolManager: Option[LedgerPoolConnManager])
                          (implicit we: WalletExt): Future[Set[VerKey]] = {
    Future.sequence {
      keys.map { rk =>
        rk.verKeyParam match {
          case Left(vk) => Future(vk)
          case Right(gvk: GetVerKeyByDIDParam) =>
            DidOpExecutor.getVerKey(gvk.did, gvk.getKeyFromPool, ledgerPoolManager)
        }
      }
    }
  }
}

trait FutureConverter {
  implicit def convertToScala[T](codeBlock: => CompletableFuture[T]): Future[T] =
    FutureConverters.toScala(codeBlock)
}
