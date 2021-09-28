package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform


trait ApiHealthCheckEndpointHandler {
  this: HttpRouteWithPlatform =>

  protected val apiHealthCheckRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("verity" / "node") {
          extractRequest { implicit req: HttpRequest => {
            extractClientIP { implicit remoteAddress =>
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              path("readiness") {
                (get & pathEnd) {
                  complete {
                    OK
                  }
                }
              } ~
                path("liveness") {
                  (get & pathEnd) {
                    complete {
                      OK
                    }
                  }
                }
            }
          }
          }
        }
      }
    }

}
