package com.evernym.integrationtests.e2e.util

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.config.AppConfigWrapper

object TestExecutionContextProvider {
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(AppConfigWrapper)
}