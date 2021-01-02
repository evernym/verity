package com.evernym.verity

import java.util.concurrent.Executors

import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.CommonConfig.VERITY_FUTURE_THREAD_POOL_SIZE
import kamon.instrumentation.executor.ExecutorInstrumentation

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits

object ExecutionContextProvider {

  lazy val customFutureThreadPoolSize: Option[Int] =
    AppConfigWrapper.getConfigIntOption(VERITY_FUTURE_THREAD_POOL_SIZE)

  /**
   * custom thread pool executor
   */
  implicit val futureExecutionContext: ExecutionContext =
    ExecutorInstrumentation.instrumentExecutionContext(
      customFutureThreadPoolSize match {
        case Some(size) => ExecutionContext.fromExecutor(Executors.newFixedThreadPool(size))
        case _          => Implicits.global
      },
    "future-thread-executor")

}
