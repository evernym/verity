package com.evernym.verity.http.route_handlers.configured

import akka.pattern.ask
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, handleExceptions, logRequestResult, pathPrefix, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.maintenance.{ActorParam, PersistentDataResp, SendAggregated, SendAll, SendSummary, SummaryData}
import com.evernym.verity.actor.node_singleton.PersistentActorQueryParam
import com.evernym.verity.actor.base.Stop
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.common.CustomExceptionHandler.{exceptionHandler, handleExpectedResponse, handleUnexpectedResponse}
import com.evernym.verity.http.common.HttpRouteBase
import com.evernym.verity.http.route_handlers.PlatformServiceProvider

import scala.concurrent.Future

trait ReadOnlyActorEndpointHandler
  extends HttpRouteBase
    with PlatformServiceProvider {

  def getPersistentActorEvents(reload: String, actorParam: ActorParam, cmd: Any): Future[Any] = {
    if (reload == YES) {
      platform.nodeSingleton ! PersistentActorQueryParam(actorParam, Stop())
    }
    platform.nodeSingleton ? PersistentActorQueryParam(actorParam, cmd)
  }

  private def handleRequest(actorParam: ActorParam, cmd: Any, inHtml: String, reload: String): Route = {
    complete {
      getPersistentActorEvents(reload, actorParam, cmd)
        .map[ToResponseMarshallable] {
          case sd: SummaryData => handleExpectedResponse(sd)
          case resp: PersistentDataResp =>
            if (inHtml == YES) {
              val respStr = resp.toRecords.mkString("<br><br>")
              HttpResponse.apply(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, respStr))
            } else {
              handleExpectedResponse(resp.toRecords.mkString("\n"))
            }
          case e => handleUnexpectedResponse(e)
        }
    }
  }

  protected val persistentActorMaintenanceRoutes: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("agency" / "internal" / "maintenance") {
          extractRequest { implicit req =>
            extractClientIP { implicit remoteAddress =>
              checkIfAddressAllowed(remoteAddress, req.uri)
              pathPrefix("persistent-actor") {
                pathPrefix(Segment / Segment) { (actorTypeName, actorEntityId) =>
                  pathPrefix("data") {
                    path("summary") {
                      (get & pathEnd) {
                        parameters(Symbol("reload") ? "N", Symbol("recoverFromSnapshot") ? "Y", Symbol("persEncKey").?) {
                          (reload, recoverFromSnapshot, persEncKey) =>
                          val actorParam = ActorParam(actorTypeName, actorEntityId, recoverFromSnapshot == YES, persEncKey)
                          handleRequest(actorParam, SendSummary, inHtml = NO, reload)
                        }
                      }
                    } ~
                      path("aggregated") {
                        (get & pathEnd) {
                          parameters(Symbol("asHtml") ? "N", Symbol("reload") ? "N", Symbol("recoverFromSnapshot") ? "Y", Symbol("persEncKey").?) {
                            (inHtml, reload, recoverFromSnapshot, persEncKey) =>
                            val actorParam = ActorParam(actorTypeName, actorEntityId, recoverFromSnapshot == YES, persEncKey)
                            handleRequest(actorParam, SendAggregated, inHtml, reload)
                          }
                        }
                      } ~
                        path("all") {
                          (get & pathEnd) {
                            parameters(Symbol("asHtml") ? "N", Symbol("withData") ? "N",Symbol("reload") ? "N", Symbol("recoverFromSnapshot") ? "Y", Symbol("persEncKey").?) {
                              (inHtml, withData, reload, recoverFromSnapshot, persEncKey) =>
                              val actorParam = ActorParam(actorTypeName, actorEntityId, recoverFromSnapshot == YES, persEncKey)
                              handleRequest(actorParam, SendAll(withData), inHtml, reload)
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
    }
}
