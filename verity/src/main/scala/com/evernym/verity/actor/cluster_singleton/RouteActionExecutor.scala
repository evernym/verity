package com.evernym.verity.actor.cluster_singleton

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import com.evernym.verity.actor.{ActorMessage, ForIdentifier}
import com.evernym.verity.actor.agent.maintenance.{ProcessPending, RegisteredRouteSummary}
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, GetRegisteredRouteSummary, GetRouteBatch, GetRouteBatchResult, InternalMsgRouteParam, RoutingAgentBucketMapperV1}
import com.evernym.verity.actor.base.{AlreadyDone, CoreActorExtended, DoNotRecordLifeCycleMetrics, Done, Ping, Stop}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants._

class RouteMaintenanceHelper(val appConfig: AppConfig, val agentMsgRouter: AgentMsgRouter)
  extends CoreActorExtended
    with DoNotRecordLifeCycleMetrics {

  override def receiveCmd: Receive = {
    case mcw: MaintenanceCmdWrapper  => getRequiredActor(mcw.taskId).forward(mcw.cmd)
  }

  def getRequiredActor(taskId: String): ActorRef = context.child(taskId).getOrElse(
    context.actorOf(RouteActionExecutor.props(appConfig, agentMsgRouter), taskId)
  )
}

class RouteActionExecutor(val appConfig: AppConfig, val agentMsgRouter: AgentMsgRouter)
  extends CoreActorExtended
    with DoNotRecordLifeCycleMetrics {

  override def receiveCmd: Receive = {
    case Init                         => handleInit()
    case ProcessPending               => processPending()
    case GetStatus                    => sender ! ActionStatus(routeStatus)
    case rs: RestartAllActors         => handleRestartAllActors(rs)
    case rrs: RegisteredRouteSummary  => handleRegisteredRouteSummary(rrs)
    case grbr: GetRouteBatchResult    => handleGetRouteBatchResult(grbr)
  }

  def handleInit(): Unit = {
    (0 to 99).foreach { i =>
      val entityId = RoutingAgentBucketMapperV1.entityIdByBucketId(i)
      routeStatus += entityId -> RouteStatus()
    }
  }

  def handleRestartAllActors(raa: RestartAllActors): Unit = {
    if (raa.restartTaskIfAlreadyRunning) {
      action = None
      routeStatus = Map.empty
    }
    if (action.isEmpty) {
      action = Option(raa)
      scheduleJob(scheduledJobId, 30, ProcessPending)
      sender ! Done
    } else {
      sender ! AlreadyDone
    }
  }

  def processPending(): Unit = {
    processPendingRouteRegistration()
    processRegisteredRoutes()
    stopActorIfTaskFinished()
  }

  def stopActorIfTaskFinished(): Unit = {
    if (routeStatus.nonEmpty && routeStatus.forall(_._2.isAllRouteProcessingCompleted)) {
      stopActor()
    }
  }

  def processPendingRouteRegistration(): Unit = {
    routeStatus
      .filter(r => ! r._2.isRegistered)
      .take(routeRegistrationBatchSize)
      .foreach { case (entityId, _) =>
        agentRouteStoreRegion ! ForIdentifier(entityId, GetRegisteredRouteSummary)
        java.lang.Thread.sleep(2000)
      }
  }

  def processRegisteredRoutes(): Unit = {
    val unregisteredRouteExists = routeStatus.exists(r => r._2.candidateRoutes == -1)
    val inProgressBatchExists = routeStatus.exists(r => r._2.isBatchInProgress)
    if (! unregisteredRouteExists && ! inProgressBatchExists) {
      routeStatus
        .filter(r => r._2.isAllRegisteredRouteProcessed && !r._2.isBatchInProgress)
        .take(routeProcessingBatchSize)
        .foreach { case (entityId, rs) =>
          val grb = GetRouteBatch(rs.candidateRoutes, rs.processedRoutes, fetchRouteBatchSize, actorTypeIds)
          agentRouteStoreRegion ! ForIdentifier(entityId, grb)
          routeStatus += (entityId -> rs.copy(isBatchInProgress = true))
          java.lang.Thread.sleep(2000)
        }
    }
  }

  def handleRegisteredRouteSummary(rrs: RegisteredRouteSummary): Unit = {
    routeStatus.get(rrs.entityId) match {
      case Some(rs) if rs.candidateRoutes == -1 =>
        routeStatus += rrs.entityId -> rs.copy(candidateRoutes = rrs.totalCandidateRoutes)
      case _ => //nothing to do
    }
    sender ! Stop()   //stop route store actor
  }

  def handleGetRouteBatchResult(grbr: GetRouteBatchResult): Unit = {
    grbr.dids.foreach { did =>
      actionCmds.foreach { cmd =>
        agentMsgRouter.forward(InternalMsgRouteParam(did, cmd), self)
      }
      java.lang.Thread.sleep(2000)
    }
    routeStatus.get(grbr.routeStoreEntityId).foreach { rs =>
      val urs = rs.copy(
        processedRoutes = rs.processedRoutes + grbr.dids.size,
        isBatchInProgress = false
      )
      if (urs.isAllRegisteredRouteProcessed) {
        sender ! Stop()   //stop route store actor
      }
      routeStatus += grbr.routeStoreEntityId -> urs
    }
  }

  self ! Init

  lazy val agentRouteStoreRegion: ActorRef =
    ClusterSharding.get(context.system).shardRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME)

  /**
   * number of routes for which 'registration' requests would be sent in one schedule job iteration
   */
  val routeRegistrationBatchSize = 5

  /**
   * number of routes for which 'get route batch' requests would be sent in one schedule job iteration
   */
  val routeProcessingBatchSize = 2

  val fetchRouteBatchSize = 5

  var routeStatus: Map[EntityId, RouteStatus] = Map.empty
  var action: Option[Any] = None

  lazy val actionCmds: List[Any] = {
    action match {
      case Some(_: RestartAllActors)  => List(Stop(), Ping(), Stop())
      case _                          => List.empty
    }
  }

  lazy val actorTypeIds: List[Int] =
    action match {
      case Some(rs: RestartAllActors)   => rs.actorTypeIdsStr.split(',').map(_.toInt).toList
      case _                            => List.empty
    }

  val scheduledJobId = "periodic_job"
}

case class RouteStatus(candidateRoutes: Int = -1,
                       processedRoutes: Int = 0,
                       isBatchInProgress: Boolean = false) {
  def isRegistered: Boolean = candidateRoutes != -1
  def isAllRegisteredRouteProcessed: Boolean = isRegistered && candidateRoutes != processedRoutes
  def isAllRouteProcessingCompleted: Boolean = isRegistered && candidateRoutes == processedRoutes
}

object RouteMaintenanceHelper {
  val name: String = ROUTE_MAINTENANCE_HELPER
  def props(appConfig: AppConfig, agentMsgRouter: AgentMsgRouter): Props =
    Props(new RouteMaintenanceHelper(appConfig, agentMsgRouter))
}

object RouteActionExecutor {
  def props(appConfig: AppConfig, agentMsgRouter: AgentMsgRouter): Props =
    Props(new RouteActionExecutor(appConfig, agentMsgRouter))
}

case class MaintenanceCmdWrapper(taskId: String, cmd: Any) extends ActorMessage

case class RestartAllActors(actorTypeIdsStr: String, restartTaskIfAlreadyRunning: Boolean) extends ActorMessage
case object GetStatus extends ActorMessage

case class ActionStatus(routeStatus: Map[EntityId, RouteStatus]) extends ActorMessage

case object Init extends ActorMessage