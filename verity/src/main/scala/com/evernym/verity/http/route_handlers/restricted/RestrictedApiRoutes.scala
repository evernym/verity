package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.evernym.verity.http.common.HttpRouteBase
import com.evernym.verity.http.route_handlers.PlatformServiceProvider

/**
 * api routes which are restricted
 * as of today, they are allowed only from localhost or configured ip addresses
 */

trait RestrictedApiRoutes
  extends HttpRouteBase
    with PlatformServiceProvider
    with HealthCheckEndpointHandler
    with ResourceUsageEndpointHandler
    with AgencySetupEndpointHandler
    with MaintenanceEndpointHandler
    with MetricsEndpointHandler
    with ItemManagerEndpointHandler
    with MsgProgressTrackerEndpointHandler {

  protected val restrictedApiRoutes: Route = healthCheckRoute ~ resourceUsageRoute ~ setupRoutes ~
    maintenanceRoutes ~ metricsRoutes ~ itemManagerRoutes ~ msgProgressTrackerRoutes
}
