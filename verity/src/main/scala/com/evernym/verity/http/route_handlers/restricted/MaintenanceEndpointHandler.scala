package com.evernym.verity.http.route_handlers.restricted

import akka.pattern.ask
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives.{complete, extractClientIP, extractRequest, handleExceptions, logRequestResult, parameters, path, pathPrefix, put, _}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.evernym.verity.actor.agent.maintenance.{ExecutorStatus, GetExecutorStatus, GetManagerStatus, ManagerStatus, Reset}
import com.evernym.verity.actor.cluster_singleton.{ActionStatus, ForActorStateCleanupManager, ForRouteMaintenanceHelper, GetStatus, MaintenanceCmdWrapper, RestartAllActors}
import com.evernym.verity.actor.persistence.{AlreadyDone, Done, Stop}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.{ConfigOverridden, ConfigRefreshed, ForIdentifier, NodeConfigOverridden, NodeConfigRefreshed, OverrideConfigOnAllNodes, OverrideNodeConfig, RefreshConfigOnAllNodes, RefreshNodeConfig}
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

  def overrideConfig(onAllNodes: String, str: String): Future[Any] = {
    if (onAllNodes == YES) {
      platform.singletonParentProxy ? OverrideConfigOnAllNodes(str)
    } else {
      platform.nodeSingleton ? OverrideNodeConfig(str)
    }
  }

  def sendToRouteMaintenanceHelper(taskId: String, cmd: Any): Future[Any] = {
    platform.singletonParentProxy ? ForRouteMaintenanceHelper(MaintenanceCmdWrapper(taskId, cmd))
  }

  def resetActorStateCleanupManager: Future[Any] = {
    platform.singletonParentProxy ? ForActorStateCleanupManager(Reset)
  }

  def updateActorStateCleanupConfig(cmd: Any): Future[Any] = {
    platform.singletonParentProxy ? ForActorStateCleanupManager(cmd)
  }

  def getActorStateManagerCleanupStatus(detailOpt: Option[String]): Future[Any] = {
    val getStatusCmd = if (detailOpt.map(_.toUpperCase).contains(YES)) {
      GetManagerStatus(includeDetails = true)
    } else GetManagerStatus()
    platform.singletonParentProxy ? ForActorStateCleanupManager(getStatusCmd)
  }

  def getActorStateCleanupExecutorStatus(entityId: String, detailOpt: Option[String]): Future[Any] = {
    val getStatusCmd = if (detailOpt.map(_.toUpperCase).contains(YES)) {
      GetExecutorStatus(includeDetails = true)
    } else GetExecutorStatus()

    platform.actorStateCleanupExecutor ? ForIdentifier(entityId, getStatusCmd)
  }

  protected val maintenanceRoutes: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("agency" / "internal" / "maintenance") {
          extractRequest { implicit req =>
            extractClientIP { implicit remoteAddress =>
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              pathPrefix("route" / "task") {
                pathPrefix(Segment) { taskId =>
                  path("restart-all-actors") {
                    (post & pathEnd) {
                      parameters('actorTypeIds ? "1,2,3,4,5,6") { actorTypeIds =>
                        complete {
                          val cmd = RestartAllActors(actorTypeIds, restartTaskIfAlreadyRunning = false)
                          sendToRouteMaintenanceHelper(taskId, cmd).map[ToResponseMarshallable] {
                            case Done | AlreadyDone => OK
                            case e => handleUnexpectedResponse(e)
                          }
                        }
                      }
                    }
                  } ~
                    path("stop") {
                      (post & pathEnd) {
                        complete {
                          sendToRouteMaintenanceHelper(taskId, Stop(sendResp = true)).map[ToResponseMarshallable] {
                            case Done => OK
                            case e => handleUnexpectedResponse(e)
                          }
                        }
                      }
                    } ~
                      path("status") {
                        (get & pathEnd) {
                          complete {
                            sendToRouteMaintenanceHelper(taskId, GetStatus).map[ToResponseMarshallable] {
                              case s: ActionStatus => handleExpectedResponse(s)
                              case e => handleUnexpectedResponse(e)
                            }
                          }
                        }
                      }
                    }
              } ~
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
                  } ~
                    path("override") {
                      (put & pathEnd & entity(as[String])) { configStr =>
                        parameters('onAllNodes ? "Y") { onAllNodes =>
                          complete {
                            overrideConfig(onAllNodes, configStr).map[ToResponseMarshallable] {
                              case NodeConfigOverridden => OK
                              case ConfigOverridden => OK
                              case e => handleUnexpectedResponse(e)
                            }
                          }
                        }
                      }
                    }
                } ~ pathPrefix("actor-state-cleanup") {
                  //TODO: this 'actor-state-cleanup' is added temporarily until
                  // the agent state cleanup (route migration etc) work is complete.
                  // After that, we will remove this.
                  path("status") {
                    (get & pathEnd) {
                      parameters('detail.?) { detailOpt =>
                        complete {
                          getActorStateManagerCleanupStatus(detailOpt).map[ToResponseMarshallable] {
                            case s: ManagerStatus => handleExpectedResponse(s)
                            case e => handleUnexpectedResponse(e)
                          }
                        }
                      }
                    }
                  } ~
                    path("reset") {
                      (post & pathEnd) {
                        complete {
                          resetActorStateCleanupManager.map[ToResponseMarshallable] {
                            case Done => OK
                            case e => handleUnexpectedResponse(e)
                          }
                        }
                      }
                    } ~
                      pathPrefix("executor") {
                        pathPrefix(Segment) { updaterEntityId =>
                          path("status") {
                            (get & pathEnd) {
                              parameters('detail.?) { detailOpt =>
                                complete {
                                  getActorStateCleanupExecutorStatus(updaterEntityId, detailOpt).map[ToResponseMarshallable] {
                                    case s: ExecutorStatus => handleExpectedResponse(s)
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
}
