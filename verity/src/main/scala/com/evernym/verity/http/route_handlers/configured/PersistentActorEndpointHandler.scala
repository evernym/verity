package com.evernym.verity.http.route_handlers.configured

import akka.pattern.ask
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, handleExceptions, logRequestResult, pathPrefix, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.maintenance.{ActorParam, PersistentDataWrapper, SendPersistedData}
import com.evernym.verity.actor.node_singleton.PersistentActorQueryParam
import com.evernym.verity.actor.base.Stop
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.common.CustomExceptionHandler.{exceptionHandler, handleExpectedResponse, handleUnexpectedResponse}
import com.evernym.verity.http.common.HttpRouteBase
import com.evernym.verity.http.route_handlers.PlatformServiceProvider

import scala.concurrent.Future

trait PersistentActorEndpointHandler
  extends HttpRouteBase
    with PlatformServiceProvider {

  def getPersistentActorEvents(actorTypeName: String,
                               actorEntityId: String,
                               reload: String,
                               recoverFromSnapshot: String,
                               persEncKeyConfPath: Option[String]): Future[Any] = {
    val actorParam = ActorParam(actorTypeName, actorEntityId, recoverFromSnapshot == YES, persEncKeyConfPath)
    if (reload == YES) {
      platform.nodeSingleton ! PersistentActorQueryParam(actorParam, Stop())
    }
    platform.nodeSingleton ? PersistentActorQueryParam(actorParam, SendPersistedData)
  }

  protected val persistentActorMaintenanceRoutes: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("agency" / "internal" / "maintenance") {
          extractRequest { implicit req =>
            extractClientIP { implicit remoteAddress =>
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              pathPrefix("persistent-actor") {
                pathPrefix(Segment / Segment) { (actorTypeName, actorEntityId) =>
                  path("data") {
                    (get & pathEnd) {
                      parameters('asHtml ? "N", 'reload ? "N", 'recoverFromSnapshot ? "Y", 'persEncKeyConfPath.?) {
                        (inHtml, reload, recoverFromSnapshot, persEncKeyConfPath) =>
                        complete {
                          getPersistentActorEvents(actorTypeName, actorEntityId, reload, recoverFromSnapshot, persEncKeyConfPath)
                            .map[ToResponseMarshallable] {
                              case pdw: PersistentDataWrapper =>
                                if (inHtml == YES) {
                                  val resp = pdw.data.map(_.toString).mkString("<br><br>")
                                  HttpResponse.apply(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, resp))
                                } else handleExpectedResponse(pdw.data.map(_.toString))
                              case e => handleUnexpectedResponse(e)
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
}
