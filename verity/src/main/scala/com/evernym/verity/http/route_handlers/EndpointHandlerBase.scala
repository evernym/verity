package com.evernym.verity.http.route_handlers

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{HttpRequest, RemoteAddress}
import akka.http.scaladsl.server.Directives.{complete, extractClientIP, extractRequest, get, handleExceptions, ignoreTrailingSlash, logRequestResult, parameters, pathPrefix, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.{AgencyPublicDid, AppStateCoordinator}
import com.evernym.verity.actor.agent.agency.GetLocalAgencyIdentity
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.resourceusagethrottling.RESOURCE_TYPE_ENDPOINT
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.common.HttpRouteBase
import com.evernym.verity.http.route_handlers.configured.ConfiguredApiRoutes
import com.evernym.verity.http.route_handlers.open.OpenApiRoutes
import com.evernym.verity.http.route_handlers.restricted.RestrictedApiRoutes
import com.evernym.verity.util.healthcheck.HealthChecker

import scala.concurrent.Future


trait EndpointHandlerBase
  extends HttpRouteBase
    with PlatformServiceProvider
    with OpenApiRoutes
    with ConfiguredApiRoutes
    with RestrictedApiRoutes {

  /**
   * this is the route provided to http server, so the 'baseRoute' variable
   * should be combining all the routes this agency instance wants to support
   * @return
   */
  def baseRoute: Route = openApiRoutes ~ restrictedApiRoutes ~ configuredApiRoutes ~ agencyRoute

  def endpointRoutes: Route = ignoreTrailingSlash { baseRoute }

  override val healthChecker: HealthChecker = platform.healthChecker
  override val appStateHandler: AppStateCoordinator = platform.appStateHandler

  protected def msgResponseHandler: PartialFunction[Any, ToResponseMarshallable] = {
    case ai: AgencyPublicDid     => handleExpectedResponse(ai)
    case e                       => handleUnexpectedResponse(e)
  }

  protected def sendToAgencyAgent(msg: Any): Future[Any] = {
    getAgencyDidPairFut flatMap { didPair =>
      platform.agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(didPair.did, msg))
    }
  }

  protected def handleGetAgencyIdentity(withDetail: Boolean)(implicit remoteAddress: RemoteAddress): Route = {
    addUserResourceUsage(RESOURCE_TYPE_ENDPOINT, "GET_agency", clientIpAddress, None)
    complete {
      sendToAgencyAgent(GetLocalAgencyIdentity(withDetail)).map[ToResponseMarshallable] {
        msgResponseHandler
      }
    }
  }

  protected val agencyRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("agency") {
          extractRequest { implicit req: HttpRequest =>
            extractClientIP { implicit remoteAddress =>
              (get & pathEnd) {
                parameters('detail.?) { detailOpt =>
                  if (detailOpt.map(_.toUpperCase).contains(YES)) {
                    handleGetAgencyIdentity(withDetail = true)
                  } else {
                    handleGetAgencyIdentity(withDetail = false)
                  }
                }
              }
            }
          }
        }
      }
    }
}
