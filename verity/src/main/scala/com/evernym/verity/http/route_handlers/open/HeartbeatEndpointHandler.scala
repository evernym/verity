package com.evernym.verity.http.route_handlers.open

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, get, path}
import com.evernym.verity.util2.Status.ACCEPTING_TRAFFIC
import com.evernym.verity.actor.appStateManager.GetHeartbeat
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.http.route_handlers.PlatformServiceProvider

import scala.concurrent.ExecutionContext

trait HeartbeatEndpointHandler
  extends ResourceUsageCommon { this: PlatformServiceProvider =>
  private implicit val executionContext: ExecutionContext = futureExecutionContext

  protected val heartbeatRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        path("agency" / "heartbeat") {
          (get & pathEnd) {
            complete {
              askAppStateManager(GetHeartbeat).map[ToResponseMarshallable] {
                case heartbeat: StatusDetailResp =>
                  heartbeat.statusCode match {
                    case ACCEPTING_TRAFFIC.statusCode =>
                      HttpResponse(status = StatusCodes.OK, entity = DefaultMsgCodec.toJson(heartbeat))
                    case _ =>
                      HttpResponse(status = StatusCodes.ServiceUnavailable, entity = DefaultMsgCodec.toJson(heartbeat))
                  }
                case e => handleUnexpectedResponse(e)
              }
            }
          }
        }
      }
    }
}