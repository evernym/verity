package com.evernym.verity.http.route_handlers.agency

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.agent.agency.GetLocalAgencyIdentity
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.resourceusagethrottling.RESOURCE_TYPE_ENDPOINT
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.common.AllowedIpsResolver.extractIp
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor

import scala.concurrent.Future


trait AgencyEndpointHandler
  extends BaseRequestHandler {
  this: PlatformWithExecutor with ResourceUsageCommon =>

  protected def agencyResponseHandler: PartialFunction[Any, ToResponseMarshallable] = {
    case ai: AgencyPublicDid => handleExpectedResponse(ai)
    case e => handleUnexpectedResponse(e)
  }

  protected def sendToAgencyAgent(withDetail: Boolean): Future[Any] = {
    getAgencyDidPairFut.flatMap { didPair =>
      val cmd = InternalMsgRouteParam(didPair.did, GetLocalAgencyIdentity(withDetail))
      platform.agentActorContext.agentMsgRouter.execute(cmd)
    }
  }

  protected val agencyRoute: Route = {
    handleRequest(exceptionHandler) { (_, remoteAddress) =>
      pathPrefix("agency") {
        (get & pathEnd) {
          parameters(Symbol("detail") ? NO) { withDetail =>
            addUserResourceUsage(RESOURCE_TYPE_ENDPOINT, "GET_agency", extractIp(remoteAddress), None)
            complete {
              sendToAgencyAgent(withDetail == YES).map {
                agencyResponseHandler
              }
            }
          }
        }
      }
    }
  }
}
