package com.evernym.verity.vault.wallet_api

import akka.actor.ActorRef
import com.evernym.verity.libindy.wallet.operation_executor.FutureConverter
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.vault.service._
import com.evernym.verity.vault.WalletAPIParam
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.language.implicitConversions

class StandardWalletAPI(walletService: WalletService)
  extends WalletAPI
    with FutureConverter {

  val logger: Logger = getLoggerByClass(classOf[WalletAPI])

  def executeAsync[T](cmd: Any)(implicit wap: WalletAPIParam): Future[T] = {
    walletService.executeAsync(wap.walletId, cmd)
  }

  def tell(cmd: Any)(implicit wap: WalletAPIParam, sender: ActorRef): Unit = {
    walletService.tell(wap.walletId, cmd)
  }
}


