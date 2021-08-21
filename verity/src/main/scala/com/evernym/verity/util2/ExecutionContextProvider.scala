package com.evernym.verity.util2
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.{VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE, VERITY_WALLET_FUTURE_THREAD_POOL_SIZE}
import kamon.instrumentation.executor.ExecutorInstrumentation

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

trait HasWalletExecutionContextProvider {
  /**
   * custom thread pool executor
   */
  def futureWalletExecutionContext: ExecutionContext
}

trait HasExecutionContextProvider {
  /**
   * custom thread pool executor
   */
  def futureExecutionContext: ExecutionContext
}

class ExecutionContextProvider(val appConfig: AppConfig) {
  private lazy val defaultFutureThreadPoolSize: Option[Int] =
    appConfig.getIntOption(VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE)
  private lazy val walletFutureThreadPoolSize: Option[Int] =
    appConfig.getIntOption(VERITY_WALLET_FUTURE_THREAD_POOL_SIZE)

  /**
   * custom thread pool executor
   */
  lazy val futureExecutionContext: ExecutionContext =
    {
      ExecutorInstrumentation.instrumentExecutionContext(
        defaultFutureThreadPoolSize match {
          case Some(size) => ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size))
          case _          => ExecutionContext.fromExecutor(null)
        },
        "future-thread-executor")
    }

  lazy val walletFutureExecutionContext: ExecutionContext =
    {
      ExecutorInstrumentation.instrumentExecutionContext(
        walletFutureThreadPoolSize match {
          case Some(size) => ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size))
          case _          => futureExecutionContext
        },
        "wallet-thread-executor")
    }
}
