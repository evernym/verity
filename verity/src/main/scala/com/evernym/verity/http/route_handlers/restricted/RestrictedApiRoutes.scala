package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.evernym.verity.http.route_handlers.PlatformWithExecutor

/**
 * api routes which are restricted
 * as of today, they are allowed only from localhost or configured ip addresses
 */

trait RestrictedApiRoutes extends HealthCheckEndpointHandler
  with ResourceUsageEndpointHandler
  with AgencySetupEndpointHandler
  with MaintenanceEndpointHandler
  with ItemStoreEndpointHandler
  with MsgProgressTrackerEndpointHandler
  with OutboxEndpointHandler {
  this: PlatformWithExecutor =>
  protected val restrictedApiRoutes: Route = healthCheckRoute ~ resourceUsageRoute ~ setupRoutes ~
    maintenanceRoutes ~ itemStoreRoutes ~ msgProgressTrackerRoutes ~ outboxRoute
}
