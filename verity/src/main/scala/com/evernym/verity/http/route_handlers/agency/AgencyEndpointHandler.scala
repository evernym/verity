package com.evernym.verity.http.route_handlers.agency

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.agent.agency.GetLocalAgencyIdentity
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.resourceusagethrottling.RESOURCE_TYPE_ENDPOINT
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor

import scala.concurrent.Future


trait AgencyEndpointHandler
  extends BaseRequestHandler {
  this: PlatformWithExecutor with ResourceUsageCommon =>

  protected def msgResponseHandler: PartialFunction[Any, ToResponseMarshallable] = {
    case ai: AgencyPublicDid => handleExpectedResponse(ai)
    case e => handleUnexpectedResponse(e)
  }

  protected def sendToAgencyAgent(msg: Any): Future[Any] = {
    getAgencyDidPairFut flatMap { didPair =>
      platform.agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(didPair.did, msg))
    }
  }

  protected def handleGetAgencyIdentity(withDetail: Boolean, remoteAddress: RemoteAddress): Route = {
    addUserResourceUsage(RESOURCE_TYPE_ENDPOINT, "GET_agency", remoteAddress.getAddress().get.getHostAddress, None)
    complete {
      sendToAgencyAgent(GetLocalAgencyIdentity(withDetail)).map[ToResponseMarshallable] {
        msgResponseHandler
      }
    }
  }

  protected val agencyRoute: Route = {
    handleRequest(exceptionHandler) { (_, remoteAddress) =>
      pathPrefix("agency") {
        (get & pathEnd) {
          parameters(Symbol("detail").?) { detailOpt =>
            if (detailOpt.map(_.toUpperCase).contains(YES)) {
              handleGetAgencyIdentity(withDetail = true, remoteAddress)
            } else {
              handleGetAgencyIdentity(withDetail = false, remoteAddress)
            }
          }
        }
      }
    }
  }
}
