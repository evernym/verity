package com.evernym.verity.http.route_handlers.configured

import akka.http.scaladsl.server.directives.RouteDirectives
import akka.http.scaladsl.server.Route
import com.evernym.verity.config.CommonConfig.URL_MAPPER_API_ENABLED

/**
 * api routes which are available if enabled via configuration
 */

trait ConfiguredApiRoutes
  extends UrlMapperEndpointHandler {

  protected val configuredApiRoutes: Route =
    urlMapperRouteIfEnabled(URL_MAPPER_API_ENABLED)

  def urlMapperRouteIfEnabled(configName: String): Route = {
    if (appConfig.getConfigBooleanOption(configName).contains(true)) {
      urlMapperRoute
    } else RouteDirectives.reject
  }
}

