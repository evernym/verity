package com.evernym.verity.app_launcher

import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.ExecutionContextProvider

class LaunchPreCheckSpec
  extends ActorSpec
    with BasicSpec {

  "LaunchPreCheck" - {
    "when asked to test external dependency" - {
      "should respond successfully" in {
        //if all the checks are successful, it should return from this function
        LaunchPreCheck.waitReqDependenciesIsOk(platform)
      }
    }
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}
