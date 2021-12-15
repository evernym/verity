package com.evernym.verity.util2
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE
import kamon.instrumentation.executor.ExecutorInstrumentation

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

trait HasExecutionContextProvider {
  /**
   * custom thread pool executor
   */
  def futureExecutionContext: ExecutionContext
}

class ExecutionContextProvider(val appConfig: AppConfig) {
  private lazy val defaultFutureThreadPoolSize: Option[Int] =
    appConfig.getIntOption(VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE)

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
}
