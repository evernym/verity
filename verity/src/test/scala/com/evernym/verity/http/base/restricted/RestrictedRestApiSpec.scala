package com.evernym.verity.http.base.restricted

import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.http.base.open.OpenRestApiSpec

trait RestrictedRestApiSpec
  extends OpenRestApiSpec
    with HeartbeatSpec
    with AppStatusHealthCheckSpec
    with ConfigHealthCheckSpec
    with AgentRouteFixHealthCheckSpec
    with ReloadConfigSpec
    with ResourceUsageSpec
    with MetricsSpec { this : EndpointHandlerBaseSpec =>

  def testRestrictedRestApis(): Unit = {

    "Agency admin" - {

      testConfigHealthCheck()

      testReloadConfig()

      testResourceUsage()

      testMetrics()

      testHeartbeat()

      testAppStateHealthCheck()

      testAgentRouteFixStatus()
    }
  }
}
