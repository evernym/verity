package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.maintenance._
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.agent.user._
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.cluster_singleton.maintenance.{GetMigrationStatus, MigrationStatusDetail}
import com.evernym.verity.actor.cluster_singleton.{ForActorStateCleanupManager, ForAgentRoutesMigrator, maintenance}
import com.evernym.verity.actor.maintenance.v1tov2migration.SetupMigratedConnection
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.HttpUtil.entityAs
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor

import scala.concurrent.Future

trait MaintenanceEndpointHandler extends BaseRequestHandler {
  this: PlatformWithExecutor =>

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

  private val routeMigrationRoutes: Route =
    pathPrefix("route-migration") {
      path("status") {
        (get & pathEnd) {
          parameters(Symbol("detail").?) { detailOpt =>
            complete {
              getAgentRouteStoreMigrationStatus(detailOpt).map {
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
          parameters(Symbol("detail").?) { detailOpt =>
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
                parameters(Symbol("detail").?) { detailOpt =>
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
          parameters(Symbol("onAllNodes") ? "N") { onAllNodes =>
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


  protected def sendToAgent(agentDID: String, cmd: ActorMessage): Future[Any] = {
    platform.agentActorContext.agentMsgRouter.execute(
      InternalMsgRouteParam(agentDID, cmd)
    )
  }

  protected def sendToConnectionMigrator(entityId: String, cmd: ActorMessage): Future[Any] = {
    platform.connectionMigrator ? ForIdentifier(entityId, cmd)
  }

  protected val v1ToV2MigrationRoutes: Route =
    pathPrefix("v1tov2migration") {

      pathPrefix("agent") {
        pathPrefix(Segment) { agentDID =>
          path("pairwiseRoutingDIDs") {
            (get & pathEnd) {
              parameters("totalItemsReceived".withDefault(0), "batchSize".withDefault(-1)) {
                case (totalItems, batchSize) =>
                  complete {
                    sendToAgent(agentDID, GetPairwiseRoutingDIDs(totalItems, batchSize)).map[ToResponseMarshallable] {
                      case s: GetPairwiseRoutingDIDsResp => handleExpectedResponse(s)
                      case e => handleUnexpectedResponse(e)
                    }
                  }
              }
            }
          }
        }
      } ~
        pathPrefix("connection") {
          pathPrefix(Segment) { pairwiseAgentDID =>
            path("diddoc") {
              (get & pathEnd) {
                complete {
                  sendToAgent(pairwiseAgentDID, GetPairwiseConnDetail).map[ToResponseMarshallable] {
                    case s: GetPairwiseConnDetailResp => handleExpectedResponse(s)
                    case e => handleUnexpectedResponse(e)
                  }
                }
              }
            }
          }
        } ~
        pathPrefix("VAS") {
          pathPrefix("agent") {
            pathPrefix(Segment) { agentDID =>
              path("walletMigrationDetail") {
                (get & pathEnd) {
                  complete {
                    sendToAgent(agentDID, GetWalletMigrationDetail).map[ToResponseMarshallable] {
                      case s: GetWalletMigrationDetailResp => handleExpectedResponse(s)
                      case e => handleUnexpectedResponse(e)
                    }
                  }
                }
              }
            }
          } ~
            pathPrefix("connection") {
              pathPrefix(Segment) { pairwiseRoutingDID =>
                path("diddoc") {
                  (post & entityAs[SetupMigratedConnection]) { smc =>
                    complete {
                      val connectionMigratorEntityId = smc.agent.agentDID + "-" + pairwiseRoutingDID
                      sendToConnectionMigrator(connectionMigratorEntityId, smc).map[ToResponseMarshallable] {
                        case Done => OK
                        case e => handleUnexpectedResponse(e)
                      }
                    }
                  }
                }
              }
            }
        } ~
        pathPrefix("CAS" / "connection") {
          pathPrefix(Segment) { pairwiseRoutingDID =>
            path("routing") {
              (put & entityAs[UpdateTheirRouting]) { utpd =>
                complete {
                  sendToAgent(pairwiseRoutingDID, utpd).map[ToResponseMarshallable] {
                    case Done => OK
                    case e => handleUnexpectedResponse(e)
                  }
                }
              }
            }
          }
        }
    }

  protected val maintenanceRoutes: Route = {
    handleRestrictedRequest(exceptionHandler) { (_, _) =>
      pathPrefix("agency" / "internal" / "maintenance") {
        actorStateCleanupMaintenanceRoutes ~
          configMaintenanceRoutes ~
          routeMigrationRoutes ~
          v1ToV2MigrationRoutes
      }
    }
  }
}
