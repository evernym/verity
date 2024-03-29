package com.evernym.verity.util

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util2.ExecutionContextProvider

object TestExecutionContextProvider {
  lazy val testAppConfig: AppConfig = new TestAppConfig()
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(testAppConfig,
    Some(new TestThreadFactory("future-thread-pool", Thread.currentThread().getThreadGroup, getClass.getClassLoader)))
}