package com.evernym.verity.util2
import java.util.concurrent.{ExecutorService, Executors}

import com.evernym.verity.config.{AppConfig, AppConfigWrapper}
import com.evernym.verity.config.ConfigConstants.{VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE, VERITY_WALLET_FUTURE_THREAD_POOL_SIZE}
import kamon.instrumentation.executor.ExecutorInstrumentation

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
    AppConfigWrapper.getIntOption(VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE)

  lazy val executor: Option[ExecutorService] =
    defaultFutureThreadPoolSize.map(size => Executors.newFixedThreadPool(size))
  lazy val walletExecutor: Option[ExecutorService] =
    walletFutureThreadPoolSize.map(size => Executors.newFixedThreadPool(size))

  /**
   * custom thread pool executor
   */
  lazy val futureExecutionContext: ExecutionContext =
    {
      ExecutorInstrumentation.instrumentExecutionContext(
        executor match {
          case Some(ex) => ExecutionContext.fromExecutor(ex)
          case _        => ExecutionContext.fromExecutor(null)
        },
        "future-thread-executor")
    }

  private lazy val walletFutureThreadPoolSize: Option[Int] =
    appConfig.getIntOption(VERITY_WALLET_FUTURE_THREAD_POOL_SIZE)

  lazy val walletFutureExecutionContext: ExecutionContext =
    {
      ExecutorInstrumentation.instrumentExecutionContext(
        walletExecutor match {
          case Some(ec) => ExecutionContext.fromExecutor(ec)
          case _          => futureExecutionContext
        },
        "wallet-thread-executor")
    }
}
