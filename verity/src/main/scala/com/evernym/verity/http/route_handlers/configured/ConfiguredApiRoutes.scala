package com.evernym.verity.http.route_handlers.configured

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.RouteDirectives
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor

/**
 * api routes which are available if enabled via configuration
 */

trait ConfiguredApiRoutes
  extends UrlMapperEndpointHandler
    with ReadOnlyActorEndpointHandler {
  this: PlatformWithExecutor =>

  protected def configuredApiRoutes: Route =
    routeIfConfigEnabled(INTERNAL_API_URL_MAPPER_ENABLED, urlMapperRoute) ~
      routeIfConfigEnabled(INTERNAL_API_PERSISTENT_DATA_ENABLED, persistentActorMaintenanceRoutes)

  def routeIfConfigEnabled(configName: String, route: Route): Route = {
    val confValue = appConfig.getBooleanOption(configName)
    if (confValue.contains(true)) route
    else RouteDirectives.reject
  }
}

