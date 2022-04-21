package com.evernym.verity.testkit

import com.evernym.verity.config.ConfigConstants.VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE
import com.typesafe.config.Config

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.util.Try

trait HasExecutionContext {
  def config: Config

  lazy val defaultFutureThreadPoolSize: Int = Try(config.getInt(VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE)).getOrElse(8)

  implicit lazy val executionContext: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(defaultFutureThreadPoolSize))
}
