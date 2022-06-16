package com.evernym.verity.vault.wallet_api

import akka.actor.ActorRef
import com.evernym.verity.actor.wallet.WalletCommand
import com.evernym.verity.vault.operation_executor.FutureConverter
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.vault.service._
import com.evernym.verity.vault.WalletAPIParam
import com.typesafe.scalalogging.Logger

import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

class StandardWalletAPI(walletService: WalletService)
  extends WalletAPI
    with FutureConverter {

  val logger: Logger = getLoggerByClass(classOf[WalletAPI])

  def executeAsync[T](cmd: WalletCommand)(implicit wap: WalletAPIParam): Future[T] = {
    walletService.executeAsync(wap.walletId, cmd)
  }

  //TODO This is to minimize initial changes in tests
  final def executeSync[T](cmd: WalletCommand)(implicit wap: WalletAPIParam): T = {
    Await.result(executeAsync(cmd), FiniteDuration(60, TimeUnit.SECONDS))
  }

  def tell(cmd: WalletCommand)(implicit wap: WalletAPIParam, sender: ActorRef): Unit = {
    walletService.tell(wap.walletId, cmd)
  }
}


