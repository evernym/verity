package com.evernym.verity.http.route_handlers.open

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.common.models.StatusDetailResp
import com.evernym.verity.http.route_handlers.PlatformServiceProvider
import com.evernym.verity.util2.Status.ACCEPTING_TRAFFIC


trait HeartbeatEndpointHandler
  extends BaseRequestHandler {
  this: PlatformServiceProvider =>

  def heartbeatStatus: StatusDetailResp = {
    import com.evernym.verity.util2.Status.{ACCEPTING_TRAFFIC, NOT_ACCEPTING_TRAFFIC}
    if (platform.appStateCoordinator.isDrainingStarted) {
      platform.appStateCoordinator.incrementPostDrainingReadinessProbeCount()
      StatusDetailResp(NOT_ACCEPTING_TRAFFIC.withMessage("draining started"))
    } else {
      StatusDetailResp(ACCEPTING_TRAFFIC.withMessage("Listening"))
    }
  }

  protected val heartbeatRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        path("agency" / "heartbeat") {
          (get & pathEnd) {
            complete {
              heartbeatStatus match {
                case statusResp: StatusDetailResp =>
                  statusResp.statusCode match {
                    case ACCEPTING_TRAFFIC.statusCode =>
                      HttpResponse(status = StatusCodes.OK, entity = DefaultMsgCodec.toJson(statusResp))
                    case _ =>
                      HttpResponse(status = StatusCodes.ServiceUnavailable, entity = DefaultMsgCodec.toJson(statusResp))
                  }
                case e => handleUnexpectedResponse(e)
              }
            }
          }
        }
      }
    }
}