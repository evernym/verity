package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.pattern.ask
import akka.http.scaladsl.server.Directives.{extractClientIP, extractRequest, handleExceptions, logRequestResult, pathPrefix, post, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.msg_tracer.progress_tracker.{ConfigureTracking, GetState, MsgProgressTrackerHtmlGenerator, RecordedStates, TrackingConfigured}
import com.evernym.verity.actor.{ForIdentifier, SendCmdToAllNodes, StartProgressTracking, StopProgressTracking}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.actor.node_singleton.{MsgProgressTrackerCache, TrackingParam, TrackingStatus}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.http.common.CustomExceptionHandler.exceptionHandler
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform

import scala.concurrent.Future

/**
 * this is not a feature code, it is just for troubleshooting purposes
 */

trait MsgProgressTrackerEndpointHandler { this: HttpRouteWithPlatform =>

  protected def configureTracking(trackingId: String, ct: ConfigureTracking): Future[Any] = {
    startTracking(trackingId).flatMap { _ =>
      platform.msgProgressTrackerRegion ? ForIdentifier(trackingId, ct)
    }
  }

  protected def startTracking(trackingId: String): Future[Any] = {
    platform.singletonParentProxy ? SendCmdToAllNodes(StartProgressTracking(TrackingParam(trackingId)))
  }

  protected def stopTracking(trackingId: String): Future[Any] = {
    platform.singletonParentProxy ? SendCmdToAllNodes(StopProgressTracking(trackingId))
  }

  protected def getAllIdsBeingTracked: Future[Any] = {
    Future.successful(MsgProgressTrackerCache(platform.actorSystem).allIdsBeingTracked)
  }

  protected def getRecordedState(trackingId: String, topReqSize: Option[Int]): Future[Any] = {
    platform.msgProgressTrackerRegion ? ForIdentifier(trackingId, GetState(topReqSize))
  }

  protected def msgProgressBackendResponseHandler: PartialFunction[Any, ToResponseMarshallable] = {
    case Done                 => HttpResponse(StatusCodes.OK, entity="OK")
    case vr @ (_: TrackingStatus | _: TrackingConfigured)
                              => handleExpectedResponse(vr)
    case other                => handleUnexpectedResponse(other)
  }

  protected val msgProgressTrackerRoutes: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("agency" / "internal" / "msg-progress-tracker") {
          extractRequest { implicit req =>
            extractClientIP { implicit remoteAddress =>
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              (get & pathEnd) {
                complete {
                  getAllIdsBeingTracked map msgProgressBackendResponseHandler
                }
              } ~
                pathPrefix(Segment) { trackingId =>
                  post {
                    complete {
                      startTracking(trackingId) map msgProgressBackendResponseHandler
                    }
                  } ~
                    delete {
                      complete {
                        stopTracking(trackingId) map msgProgressBackendResponseHandler
                      }
                    } ~
                    get {
                      parameters(Symbol("onlyTopN").?, Symbol("withDetail").?, Symbol("inHtml").?) {
                        (onlyTopN, withDetail, inHtml) =>
                          complete {
                            val topReqSize = onlyTopN.map(_.toInt)
                            val includeDetail = withDetail.contains("Y")
                            getRecordedState(trackingId, topReqSize) map {
                              case rs: RecordedStates =>
                                if (inHtml.contains("Y")) {
                                  val htmlResp = MsgProgressTrackerHtmlGenerator.generateRequestsInHtml(rs, includeDetail)
                                  HttpResponse.apply(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, htmlResp))
                                } else {
                                  handleExpectedResponse(rs)
                                }
                              case x => handleUnexpectedResponse(x)
                            }
                          }
                      }
                    } ~
                    path("configure") {
                      (put & entityAs[ConfigureTracking]) { ct =>
                        complete {
                          configureTracking(trackingId, ct) map msgProgressBackendResponseHandler
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
