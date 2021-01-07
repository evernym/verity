package com.evernym.verity

import java.util.concurrent.Executors

import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.CommonConfig._
import kamon.instrumentation.executor.ExecutorInstrumentation

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits

object ExecutionContextProvider {

  lazy val defaultFutureThreadPoolSize: Option[Int] =
    AppConfigWrapper.getConfigIntOption(VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE)

  lazy val walletFutureThreadPoolSize: Option[Int] =
    AppConfigWrapper.getConfigIntOption(VERITY_WALLET_FUTURE_THREAD_POOL_SIZE)

  /**
   * custom thread pool executor
   */
  implicit val futureExecutionContext: ExecutionContext =
    ExecutorInstrumentation.instrumentExecutionContext(
      defaultFutureThreadPoolSize match {
        case Some(size) => ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size))
        case _          => Implicits.global
      },
    "future-thread-executor")

  implicit val walletFutureExecutionContext: ExecutionContext =
    ExecutorInstrumentation.instrumentExecutionContext(
      walletFutureThreadPoolSize match {
        case Some(size) => ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size))
        case _          => futureExecutionContext
      },
      "wallet-thread-executor")
}
