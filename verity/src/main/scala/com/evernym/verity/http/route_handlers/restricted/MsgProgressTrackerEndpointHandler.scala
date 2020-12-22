package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.pattern.ask
import akka.http.scaladsl.server.Directives.{extractClientIP, extractRequest, handleExceptions, logRequestResult, pathPrefix, post, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.ReqId
import com.evernym.verity.actor.msg_tracer.progress_tracker.{ConfigureTracking, RecordedRequests, TrackingConfigured}
import com.evernym.verity.actor.{ForIdentifier, SendCmdToAllNodes, StartProgressTracking, StopProgressTracking}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.actor.node_singleton.{MsgProgressTrackerCache, TrackingIds}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.http.common.CustomExceptionHandler.exceptionHandler
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.msg_tracer.progress_tracker.{MsgProgressTrackerHtmlGenerator, PinstIdLinkDetail}

import scala.concurrent.Future

/**
 * this is not a feature code, it is just for troubleshooting purposes
 */

trait MsgProgressTrackerEndpointHandler { this: HttpRouteWithPlatform =>

  def configureTracking(trackingId: String, ct: ConfigureTracking): Future[Any] = {
    platform.msgProgressTrackerRegion ? ForIdentifier(trackingId, ct)
  }

  def startTracking(trackingId: String): Future[Any] = {
    platform.singletonParentProxy ? SendCmdToAllNodes(StartProgressTracking(trackingId))
  }

  def stopTracking(trackingId: String): Future[Any] = {
    platform.singletonParentProxy ? SendCmdToAllNodes(StopProgressTracking(trackingId))
  }

  def getAllIdsBeingTracked: Future[Any] = {
    Future.successful(MsgProgressTrackerCache.allIdsBeingTracked)
  }



  def getRecordedEvents(forIpAddress: String, reqId: Option[ReqId],
                        domainTrackingId: Option[String],
                        relTrackingId: Option[String],
                        withEvents: Option[String],
                        inHtml: Option[String]=Some("Y")): Future[Any] = {
    MsgProgressTracker.getRecordedRequests(forIpAddress, reqId, domainTrackingId, relTrackingId, withEvents)
  }

  def msgProgressBackendResponseHandler: PartialFunction[Any, ToResponseMarshallable] = {
    case Done                 => HttpResponse(StatusCodes.OK, entity="OK")
    case vr @ (_: TrackingIds | _: TrackingConfigured)
                              => handleExpectedResponse(vr)
    case rr:RecordedRequests  => handleExpectedResponse(rr.requests)
    case other                => handleUnexpectedResponse(other)
  }

  def generatePinstLinkDetail(reqUriPath: String): PinstIdLinkDetail = {
    val prefix = reqUriPath.substring(0, reqUriPath.lastIndexOf("/"))
    PinstIdLinkDetail(prefix, "?withEvents=Y")
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
                      parameters('reqId.?, 'domainTrackingId.?, 'relTrackingId.?, 'withEvents.?, 'inHtml ? "Y") {
                        (reqId, domainTrackingId, relTrackingId, withEvents, inHtml) =>
                          complete {
                            getRecordedEvents(trackingId, reqId, domainTrackingId, relTrackingId, withEvents) map {
                              case rr: RecordedRequests =>
                                inHtml match {
                                  case "Y" =>
                                    val pinstIdLinkDetail = generatePinstLinkDetail(req.uri.toString())
                                    val htmlResp = MsgProgressTrackerHtmlGenerator.generateRequestsInHtml(trackingId, rr, Option(pinstIdLinkDetail))
                                    HttpResponse.apply(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, htmlResp))
                                  case _ => handleExpectedResponse(rr)
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
