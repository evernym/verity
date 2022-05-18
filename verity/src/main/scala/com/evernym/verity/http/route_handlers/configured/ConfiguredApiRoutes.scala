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


  //NOTE: make sure only non restricted routes are added here
  private def configuredNonRestrictedRoutes: Route = routeIfConfigEnabled(INTERNAL_API_URL_MAPPER_ENABLED, urlMapperRoute)

  //NOTE: make sure only restricted routes are added here
  private def configuredRestrictedRoutes: Route = routeIfConfigEnabled(INTERNAL_API_PERSISTENT_DATA_ENABLED, persistentActorMaintenanceRoutes)

  //NOTE: don't change the order else it will break the apis
  protected def configuredApiRoutes: Route =
    configuredNonRestrictedRoutes ~ configuredRestrictedRoutes


  def routeIfConfigEnabled(configName: String, route: Route): Route = {
    val confValue = appConfig.getBooleanOption(configName)
    if (confValue.contains(true)) route
    else RouteDirectives.reject
  }
}

