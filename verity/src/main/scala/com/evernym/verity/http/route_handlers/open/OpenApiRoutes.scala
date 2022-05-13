package com.evernym.verity.http.route_handlers.open

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.evernym.verity.http.route_handlers.{PlatformServiceProvider, PlatformWithExecutor}

/**
 * api routes which are public/open for everyone
 */

trait OpenApiRoutes
  extends PackedMsgEndpointHandler
    with RestApiEndpointHandler
    with GetInviteRestEndpointHandler
    with HeartbeatEndpointHandler {
  this: PlatformWithExecutor =>

  protected val openApiRoutes: Route = packedMsgRoute ~ restRoutes ~ getInviteRoute ~ heartbeatRoute
}
