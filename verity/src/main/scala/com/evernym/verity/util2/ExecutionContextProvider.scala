package com.evernym.verity.util2
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE
import kamon.instrumentation.executor.ExecutorInstrumentation

import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.ExecutionContext

trait HasExecutionContextProvider {
  /**
   * custom thread pool executor
   */
  def futureExecutionContext: ExecutionContext
}

class ExecutionContextProvider(val appConfig: AppConfig, threadFactory: Option[ThreadFactory] = None) {
  private lazy val defaultFutureThreadPoolSize: Int =
    appConfig.getIntOption(VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE).getOrElse(8)

  /**
   * custom thread pool executor
   */
  lazy val futureExecutionContext: ExecutionContext =
    {
      val threadPool = threadFactory match {
        case Some(tf) => Executors.newFixedThreadPool(defaultFutureThreadPoolSize, tf)
        case None => Executors.newFixedThreadPool(defaultFutureThreadPoolSize)
      }
      ExecutorInstrumentation.instrumentExecutionContext(
        ExecutionContext.fromExecutor(threadPool),
        "future-thread-executor"
      )
    }
}
