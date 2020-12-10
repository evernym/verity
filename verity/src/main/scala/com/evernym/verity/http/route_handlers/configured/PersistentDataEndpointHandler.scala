package com.evernym.verity.http.route_handlers.configured

import akka.pattern.ask
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, handleExceptions, logRequestResult, pathPrefix, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.maintenance.{ActorParam, PersistentData, SendPersistedData}
import com.evernym.verity.actor.node_singleton.PersistentActorQueryParam
import com.evernym.verity.actor.persistence.Stop
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.common.CustomExceptionHandler.{exceptionHandler, handleExpectedResponse, handleUnexpectedResponse}
import com.evernym.verity.http.common.HttpRouteBase
import com.evernym.verity.http.route_handlers.PlatformServiceProvider

import scala.concurrent.Future

trait PersistentDataEndpointHandler
  extends HttpRouteBase
    with PlatformServiceProvider {

  def getPersistentActorEvents(reload: String,
                               actorTypeName: String,
                               actorEntityId: String,
                               persEncKeyConfPath: Option[String]): Future[Any] = {
    val actorParam = ActorParam(actorTypeName, actorEntityId, persEncKeyConfPath)
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
                      parameters('asHtml ? "N", 'reload ? "N", 'persEncKeyConfPath.?) { (inHtml, reload, persEncKeyConfPath) =>
                        complete {
                          getPersistentActorEvents(reload, actorTypeName, actorEntityId, persEncKeyConfPath)
                            .map[ToResponseMarshallable] {
                              case pe: PersistentData =>
                                val allRecords = pe.snapshotStat ++ pe.events
                                if (inHtml == YES) {
                                  val resp = allRecords.map(_.toString).mkString("<br><br>")
                                  HttpResponse.apply(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, resp))
                                } else handleExpectedResponse(allRecords.map(_.toString))
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
