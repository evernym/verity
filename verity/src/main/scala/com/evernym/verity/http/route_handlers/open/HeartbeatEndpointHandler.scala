package com.evernym.verity.http.route_handlers.open

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, get, path}
import com.evernym.verity.util2.Status.ACCEPTING_TRAFFIC
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.http.route_handlers.PlatformServiceProvider

import scala.concurrent.ExecutionContext

trait HeartbeatEndpointHandler
  extends ResourceUsageCommon { this: PlatformServiceProvider =>
  private implicit val executionContext: ExecutionContext = futureExecutionContext

  def heartbeatStatus: StatusDetailResp = {
    import com.evernym.verity.util2.Status.{ACCEPTING_TRAFFIC, NOT_ACCEPTING_TRAFFIC}
    if (platform.appStateHandler.isDrainingStarted) {
      platform.appStateHandler.incrementPostDrainingReadinessProbeCount()
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