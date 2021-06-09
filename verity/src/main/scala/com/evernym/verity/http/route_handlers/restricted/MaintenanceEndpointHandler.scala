package com.evernym.verity.http.route_handlers.restricted

import akka.pattern.ask
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives.{complete, extractClientIP, extractRequest, handleExceptions, logRequestResult, parameters, path, pathPrefix, put, _}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.evernym.verity.actor.agent.maintenance.{ExecutorStatus, GetExecutorStatus, GetManagerStatus, ManagerStatus, Reset, StartJob, StopJob}
import com.evernym.verity.actor.cluster_singleton.{ForActorStateCleanupManager, ForAgentRoutesMigrator, ForRouteMaintenanceHelper, maintenance}
import com.evernym.verity.actor.base.{AlreadyDone, Done, Stop}
import com.evernym.verity.actor.cluster_singleton.maintenance.{ActionStatus, GetMigrationStatus, GetStatus, MaintenanceCmdWrapper, MigrationStatusDetail, RestartAllActors}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.{ConfigRefreshed, ForIdentifier, NodeConfigRefreshed, OverrideConfigOnAllNodes, OverrideNodeConfig, RefreshConfigOnAllNodes, RefreshNodeConfig}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform

import scala.concurrent.Future

trait MaintenanceEndpointHandler { this: HttpRouteWithPlatform =>

  implicit val responseTimeout: Timeout

  protected def reloadConfig(onAllNodes: String): Future[Any] = {
    if (onAllNodes == YES) {
      platform.singletonParentProxy ? RefreshConfigOnAllNodes
    } else {
      platform.nodeSingleton ? RefreshNodeConfig
    }
  }

  protected def overrideConfig(onAllNodes: String, str: String): Future[Any] = {
    if (onAllNodes == YES) {
      platform.singletonParentProxy ? OverrideConfigOnAllNodes(str)
    } else {
      platform.nodeSingleton ? OverrideNodeConfig(str)
    }
  }

  protected def sendToRouteMaintenanceHelper(taskId: String, cmd: Any): Future[Any] = {
    platform.singletonParentProxy ? ForRouteMaintenanceHelper(MaintenanceCmdWrapper(taskId, cmd))
  }

  protected def stopActorStateCleanupManager(): Future[Any] = {
    platform.singletonParentProxy ? ForActorStateCleanupManager(StopJob)
  }

  protected def startActorStateCleanupManager(): Future[Any] = {
    platform.singletonParentProxy ? ForActorStateCleanupManager(StartJob)
  }

  protected def restartActorStateCleanupManager(): Future[Any] = {
    stopActorStateCleanupManager()
    startActorStateCleanupManager()
  }

  protected def resetActorStateCleanupManager(): Future[Any] = {
    platform.singletonParentProxy ? ForActorStateCleanupManager(Reset)
  }

  protected def updateActorStateCleanupConfig(cmd: Any): Future[Any] = {
    platform.singletonParentProxy ? ForActorStateCleanupManager(cmd)
  }

  protected def getActorStateManagerCleanupStatus(detailOpt: Option[String]): Future[Any] = {
    val getStatusCmd = if (detailOpt.map(_.toUpperCase).contains(YES)) {
      GetManagerStatus(includeDetails = true)
    } else GetManagerStatus()
    platform.singletonParentProxy ? ForActorStateCleanupManager(getStatusCmd)
  }

  protected def getActorStateCleanupExecutorStatus(entityId: String, detailOpt: Option[String]): Future[Any] = {
    val getStatusCmd = if (detailOpt.map(_.toUpperCase).contains(YES)) {
      GetExecutorStatus(includeDetails = true)
    } else GetExecutorStatus()

    platform.actorStateCleanupExecutor ? ForIdentifier(entityId, getStatusCmd)
  }

  protected def getAgentRouteStoreMigrationStatus(detailOpt: Option[String]): Future[Any] = {
    platform.singletonParentProxy ? ForAgentRoutesMigrator(GetMigrationStatus(detailOpt))
  }

  protected def resetAgentRoutesMigrator(): Future[Any] = {
    platform.singletonParentProxy ? ForAgentRoutesMigrator(maintenance.Reset)
  }

  protected def stopAgentRoutesMigrator(): Future[Any] = {
    platform.singletonParentProxy ? ForAgentRoutesMigrator(maintenance.StopJob)
  }

  protected def startAgentRoutesMigrator(): Future[Any] = {
    platform.singletonParentProxy ? ForAgentRoutesMigrator(maintenance.StartJob)
  }

  protected def restartAgentRoutesMigrator(): Future[Any] = {
    stopAgentRoutesMigrator()
    startAgentRoutesMigrator()
  }

  private val routeMaintenanceRoutes: Route =
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
                sendToRouteMaintenanceHelper(taskId, Stop(sendAck = true)).map[ToResponseMarshallable] {
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
    }

  private val routeMigrationRoutes: Route =
    pathPrefix("route-migration") {
      path("status") {
        (get & pathEnd) {
          parameters('detail.?) { detailOpt =>
            complete {
              getAgentRouteStoreMigrationStatus(detailOpt).map[ToResponseMarshallable] {
                case msd: MigrationStatusDetail => handleExpectedResponse(msd)
                case e => handleUnexpectedResponse(e)
              }
            }
          }
        }
      } ~
          path("restart") {
            (post & pathEnd) {
              complete {
                restartAgentRoutesMigrator().map[ToResponseMarshallable] {
                  case Done => OK
                  case e => handleUnexpectedResponse(e)
                }
              }
            }
          } ~
            path("stop") {
              (post & pathEnd) {
                complete {
                  stopAgentRoutesMigrator().map[ToResponseMarshallable] {
                    case Done => OK
                    case e => handleUnexpectedResponse(e)
                  }
                }
              }
            } ~
              path("start") {
                (post & pathEnd) {
                  complete {
                    startAgentRoutesMigrator().map[ToResponseMarshallable] {
                      case Done => OK
                      case e => handleUnexpectedResponse(e)
                    }
                  }
                }
              } ~
              path("reset") {
                (post & pathEnd) {
                  complete {
                    resetAgentRoutesMigrator().map[ToResponseMarshallable] {
                      case Done => OK
                      case e => handleUnexpectedResponse(e)
                    }
                  }
                }
              }
    }

  private val actorStateCleanupMaintenanceRoutes: Route =
    pathPrefix("actor-state-cleanup") {
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
              resetActorStateCleanupManager().map[ToResponseMarshallable] {
                case Done => OK
                case e => handleUnexpectedResponse(e)
              }
            }
          }
        } ~
        path("restart") {
          (post & pathEnd) {
            complete {
              restartActorStateCleanupManager().map[ToResponseMarshallable] {
                case Done => OK
                case e => handleUnexpectedResponse(e)
              }
            }
          }
        } ~
        path("stop") {
          (post & pathEnd) {
            complete {
              stopActorStateCleanupManager().map[ToResponseMarshallable] {
                case Done => OK
                case e => handleUnexpectedResponse(e)
              }
            }
          }
        } ~
        path("start") {
          (post & pathEnd) {
            complete {
              startActorStateCleanupManager().map[ToResponseMarshallable] {
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

  private val configMaintenanceRoutes: Route =
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
//      ~ path("override") {
//          (put & pathEnd & entity(as[String])) { configStr =>
//            parameters('onAllNodes ? "Y") { onAllNodes =>
//              complete {
//                overrideConfig(onAllNodes, configStr).map[ToResponseMarshallable] {
//                  case NodeConfigOverridden => OK
//                  case ConfigOverridden => OK
//                  case e => handleUnexpectedResponse(e)
//                }
//              }
//            }
//          }
//        }
    }

  protected val maintenanceRoutes: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("agency" / "internal" / "maintenance") {
          extractRequest { implicit req =>
            extractClientIP { implicit remoteAddress =>
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              routeMaintenanceRoutes ~ actorStateCleanupMaintenanceRoutes ~ configMaintenanceRoutes ~ routeMigrationRoutes
            }
          }
        }
      }
    }
}
