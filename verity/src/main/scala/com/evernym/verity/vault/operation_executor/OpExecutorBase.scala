package com.evernym.verity.vault.operation_executor

import java.util.concurrent.CompletableFuture
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.actor.wallet.GetVerKeyResp
import com.evernym.verity.util2.Exceptions
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyParam, WalletExt}
import com.evernym.vdrtools.IndyException

import scala.concurrent.ExecutionContext
import scala.language.implicitConversions
import scala.compat.java8.FutureConverters
import scala.concurrent.{ExecutionException, Future}

trait OpExecutorBase extends FutureConverter {

  def verKeyFuture(keys: Set[KeyParam], ledgerPoolManager: Option[LedgerPoolConnManager])
                          (implicit we: WalletExt, ec: ExecutionContext): Future[Set[GetVerKeyResp]] = {
    Future.sequence {
      keys.map { rk =>
        rk.verKeyParam match {
          case Left(vk) => Future(GetVerKeyResp(vk))
          case Right(gvk: GetVerKeyByDIDParam) =>
            DidOpExecutor.getVerKey(gvk.did, gvk.getKeyFromPool, ledgerPoolManager)
        }
      }
    }
  }

  def buildOptionErrorDetail(e: Throwable): Option[String] = {
    Option(buildErrorDetail(e))
  }

  def buildErrorDetail(e: Throwable): String = {
    e match {
      case ee: ExecutionException =>
        Option(ee.getCause) match {
          case Some(ie: IndyException) =>
            ie.getSdkMessage + "\n" + Exceptions.getStackTraceAsSingleLineString(ie)
          case Some(other: Exception) =>
            other.getMessage + "\n" + Exceptions.getStackTraceAsSingleLineString(other)
          case _ =>
            ee.getMessage + "\n" + Exceptions.getStackTraceAsSingleLineString(ee)
        }
      case other =>
        other.getMessage + "\n" + Exceptions.getStackTraceAsSingleLineString(other)
    }
  }
}

trait FutureConverter {
  implicit def convertToScala[T](codeBlock: => CompletableFuture[T]): Future[T] =
    FutureConverters.toScala(codeBlock)
}
