package com.evernym.verity.http.route_handlers

import akka.http.scaladsl.server.Directives.{ignoreTrailingSlash, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.http.route_handlers.agency.AgencyApiRoutes
import com.evernym.verity.http.route_handlers.configured.ConfiguredApiRoutes
import com.evernym.verity.http.route_handlers.open.OpenApiRoutes
import com.evernym.verity.http.route_handlers.restricted.RestrictedApiRoutes


trait HttpRoutes
  extends PlatformServiceProvider
    with HasExecutor
    with ResourceUsageCommon
    with AgencyApiRoutes
    with OpenApiRoutes
    with ConfiguredApiRoutes
    with RestrictedApiRoutes {
  /**
   * this is the route provided to http server, so the 'baseRoute' variable
   * should be combining all the routes this agency instance wants to support
   *
   * @return
   */
  def baseRoute: Route = openApiRoutes ~ restrictedApiRoutes ~ configuredApiRoutes ~ agencyApiRoutes

  def endpointRoutes: Route = ignoreTrailingSlash {
    baseRoute
  }

}
