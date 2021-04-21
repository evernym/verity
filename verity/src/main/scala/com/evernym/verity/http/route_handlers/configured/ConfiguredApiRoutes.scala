package com.evernym.verity.http.route_handlers.configured

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.RouteDirectives
import akka.http.scaladsl.server.Route
import com.evernym.verity.config.CommonConfig._

/**
 * api routes which are available if enabled via configuration
 */

trait ConfiguredApiRoutes
  extends UrlMapperEndpointHandler
    with PersistentActorEndpointHandler {

  protected def configuredApiRoutes: Route =
    routeIfConfigEnabled(INTERNAL_API_URL_MAPPER_ENABLED, urlMapperRoute) ~
      routeIfConfigEnabled(INTERNAL_API_PERSISTENT_DATA_ENABLED, persistentActorMaintenanceRoutes)

  def routeIfConfigEnabled(configName: String, route: Route): Route = {
    val confValue = appConfig.getConfigBooleanOption(configName)
    if (confValue.contains(true)) route
    else RouteDirectives.reject
  }
}

