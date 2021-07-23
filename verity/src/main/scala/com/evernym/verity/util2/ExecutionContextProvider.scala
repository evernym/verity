package com.evernym.verity.util2

import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.ConfigConstants.{VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE, VERITY_WALLET_FUTURE_THREAD_POOL_SIZE}
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits

object ExecutionContextProvider {

  lazy val defaultFutureThreadPoolSize: Option[Int] =
    AppConfigWrapper.getIntOption(VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE)

  lazy val walletFutureThreadPoolSize: Option[Int] =
    AppConfigWrapper.getIntOption(VERITY_WALLET_FUTURE_THREAD_POOL_SIZE)

  /**
   * custom thread pool executor
   */
  implicit val futureExecutionContext: ExecutionContext =
    defaultFutureThreadPoolSize match {
      case Some(size) => ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size))
      case _ => Implicits.global
    }

  implicit val walletFutureExecutionContext: ExecutionContext =
    walletFutureThreadPoolSize match {
      case Some(size) => ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size))
      case _ => futureExecutionContext
    }

}
