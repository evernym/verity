package com.evernym.verity.util

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig

object TestExecutionContextProvider {
  lazy val testAppConfig: AppConfig = new TestAppConfig()
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(testAppConfig)
}
