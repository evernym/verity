package com.evernym.verity.http.route_handlers.agency

import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.http.route_handlers.PlatformWithExecutor

/**
 * api routes which for agency
 */

trait AgencyApiRoutes extends AgencyEndpointHandler {
  this: PlatformWithExecutor with ResourceUsageCommon =>
  protected val agencyApiRoutes: Route = agencyRoute
}
