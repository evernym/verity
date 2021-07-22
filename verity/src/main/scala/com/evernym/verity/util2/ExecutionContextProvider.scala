package com.evernym.verity.util2

import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.CommonConfig.{VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE, VERITY_WALLET_FUTURE_THREAD_POOL_SIZE}

//TODO: do we want to abstract this kamon instrumentation api as well as we did for metrics api?
import kamon.instrumentation.executor.ExecutorInstrumentation

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
    ExecutorInstrumentation.instrumentExecutionContext(
      defaultFutureThreadPoolSize match {
        case Some(size) => ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size))
        case _ => Implicits.global
      },
      "future-thread-executor")

  implicit val walletFutureExecutionContext: ExecutionContext =
    ExecutorInstrumentation.instrumentExecutionContext(
      walletFutureThreadPoolSize match {
        case Some(size) => ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size))
        case _ => futureExecutionContext
      },
      "wallet-thread-executor")

}
