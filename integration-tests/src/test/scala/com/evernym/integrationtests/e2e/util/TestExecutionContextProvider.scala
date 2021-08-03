package com.evernym.integrationtests.e2e.util

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.util2.ExecutionContextProvider

object TestExecutionContextProvider {
  lazy val appConfig = new TestAppConfig()
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
}