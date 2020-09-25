package com.evernym.verity.http.route_handlers.restricted

import akka.pattern.ask
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives.{complete, extractClientIP, extractRequest, handleExceptions, logRequestResult, parameters, path, pathPrefix, put}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.evernym.verity.actor.agent.msgrouter.{CurrentStatus, GetUpdaterStatus}
import com.evernym.verity.actor.cluster_singleton.ForLegacyRouteFixManager
import com.evernym.verity.actor.cluster_singleton.legacyroutefixmanager.{GetStatus, Reset, Status}
import com.evernym.verity.actor.persistence.Done
import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.{ConfigRefreshed, ForIdentifier, NodeConfigRefreshed, RefreshConfigOnAllNodes, RefreshNodeConfig}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform

import scala.concurrent.Future

trait MaintenanceEndpointHandler { this: HttpRouteWithPlatform =>

  implicit val akkActorResponseTimeout: Timeout

  def reloadConfig(onAllNodes: String): Future[Any] = {
    if (onAllNodes == YES) {
      platform.singletonParentProxy ? RefreshConfigOnAllNodes
    } else {
      platform.nodeSingleton ? RefreshNodeConfig
    }
  }

  def resetLegacyRouteFix: Future[Any] = {
    platform.singletonParentProxy ? ForLegacyRouteFixManager(Reset)
  }

  def getLegacyRouteFixStatus: Future[Any] = {
    platform.singletonParentProxy ? ForLegacyRouteFixManager(GetStatus)
  }

  def getAgentRouteStoreFixStatus(entityId: String): Future[Any] = {
    platform.legacyRouteUpdater ? ForIdentifier(entityId, GetUpdaterStatus)
  }

  protected val maintenanceRoutes: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("agency" / "internal" / "maintenance") {
          extractRequest { implicit req =>
            extractClientIP { implicit remoteAddress =>
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              pathPrefix("config") {
                path("reload") {
                  (put & pathEnd) {
                    parameters('onAllNodes ? "N") { onAllNodes =>
                      complete {
                        reloadConfig(onAllNodes).map[ToResponseMarshallable] {
                          case NodeConfigRefreshed => OK
                          case ConfigRefreshed => OK
                          case e => handleUnexpectedResponse(e)
                        }
                      }
                    }
                  }
                }
              } ~ pathPrefix("agent-route-fix") {
                //TODO: this 'agent-route-fix' is added temporarily until
                //the agent route migration work is complete. After that, we will remove this.
                path("status") {
                  (get & pathEnd) {
                    complete {
                      getLegacyRouteFixStatus.map[ToResponseMarshallable] {
                        case s: Status => handleExpectedResponse(s)
                        case e => handleUnexpectedResponse(e)
                      }
                    }
                  }
                } ~
                  path("reset") {
                    (post & pathEnd) {
                      complete {
                        resetLegacyRouteFix.map[ToResponseMarshallable] {
                          case Done => OK
                          case e => handleUnexpectedResponse(e)
                        }
                      }
                    }
                  } ~
                    pathPrefix("updater") {
                      pathPrefix(Segment) { updaterEntityId =>
                        path("status") {
                          (get & pathEnd) {
                            complete {
                              getAgentRouteStoreFixStatus(updaterEntityId).map[ToResponseMarshallable] {
                                case s: CurrentStatus => handleExpectedResponse(s)
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
