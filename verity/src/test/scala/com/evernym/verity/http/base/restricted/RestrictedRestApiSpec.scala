package com.evernym.verity.http.base.restricted

import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.http.base.open.OpenRestApiSpec

trait RestrictedRestApiSpec
  extends OpenRestApiSpec
    with HeartbeatSpec
    with AppStatusHealthCheckSpec
    with ConfigHealthCheckSpec
    with ActorStateCleanupHealthCheckSpec
    with ReloadConfigSpec
    with ApiHealthCheckSpec
    with ResourceUsageSpec { this : EdgeEndpointBaseSpec =>

  def testRestrictedRestApis(): Unit = {

    "Agency admin" - {

      testConfigHealthCheck()

      testReloadConfig()

      testResourceUsage()

      testAppStateHealthCheck()

      testAgentRouteFixStatus()

      testHeartbeat()

      testBaseApiHeathCheck()
    }
  }
}
