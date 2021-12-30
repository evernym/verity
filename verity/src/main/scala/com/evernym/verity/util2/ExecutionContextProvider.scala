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
  private lazy val defaultFutureThreadPoolSize: Option[Int] = Some(8)
    //appConfig.getIntOption(VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE)

  /**
   * custom thread pool executor
   */
  lazy val futureExecutionContext: ExecutionContext =
    {
      ExecutorInstrumentation.instrumentExecutionContext(
        defaultFutureThreadPoolSize match {
          case Some(size) =>
            threadFactory.map {
              tf => ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size, tf))
            }getOrElse {
              ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size))
            }
          case _          => ExecutionContext.fromExecutor(null)
        },
        "future-thread-executor")
    }
}
