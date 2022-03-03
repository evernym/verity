package com.evernym.verity.actor.agent.msgrouter.legacy

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import akka.event.LoggingReceive
import akka.pattern.ask
import com.evernym.verity.actor.agent.SetMissingRoute
import com.evernym.verity.util2.RouteId
import com.evernym.verity.actor.agent.maintenance.{AlreadyCompleted, AlreadyRegistered, RegisteredRouteSummary}
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, AgentMsgRouter, InternalMsgRouteParam, RouteAlreadySet, RoutingAgentUtil, StoreRoute}
import com.evernym.verity.actor.cluster_singleton.ForAgentRoutesMigrator
import com.evernym.verity.actor.cluster_singleton.maintenance.RecordMigrationStatus
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, LegacyRouteSet, Registered, RouteSet, RoutesMigrated}
import com.evernym.verity.config.{AppConfig, ConfigConstants}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.did.DidStr
import com.evernym.verity.util.Util.getActorRefFromSelection

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

/**
 * stores agent routing details (it DOESN'T do any message routing itself)
 * this is used as a sharded actor and one actor instance stores more than one routes
 *
 * see 'RoutingAgentBucketMapperV1' to know how it is decided for which DID
 * it will go to which actor instance (either for store or get)
 *
 * @param appConfig application config
 */
class LegacyAgentRouteStore(agentMsgRouter: AgentMsgRouter,
                            executionContext: ExecutionContext)(implicit val appConfig: AppConfig)
  extends BasePersistentActor {

  override def futureExecutionContext: ExecutionContext = executionContext

  override val receiveCmd: Receive = receiveBaseCmd orElse receiveOtherCmd

  lazy val receiveBaseCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case sr: LegacySetRoute if routes.contains(sr.forDID) => sender() ! RouteAlreadySet(sr.forDID)

    case sr: LegacySetRoute =>
      writeApplyAndSendItBack(
        LegacyRouteSet(sr.forDID, sr.actorAddressDetail.actorTypeId, sr.actorAddressDetail.address))
    case gr: LegacyGetRoute             => handleGetRoute(gr)
  }

  lazy val receiveOtherCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case GetRegisteredRouteSummary      =>
      sender() ! RegisteredRouteSummary(entityId, orderedRoutes.routes.size)

    case grd: GetRouteBatch             => handleGetRouteBatch(grd)

    case mp: MigratePending             => migratePending(mp)

    case FixRoute(routeId, aad)         => fixRouting(routeId, aad)(executionContext)

    case FinishRouteMigration(routeId)  => finishRouteMigration(routeId)

    case GetRouteStoreMigrationStatus   =>
      sender() ! RouteStoreMigrationStatus(timers.isTimerActive("migrate"), routes.size, migrationStatus)

    case _ @ (_: Registered | AlreadyRegistered | AlreadyCompleted | _: RouteSet) => //nothing to do
  }

  override val receiveEvent: Receive = {
    case rs: LegacyRouteSet =>
      val aad = ActorAddressDetail(rs.actorTypeId, rs.address)
      routes = routes.updated(rs.forDID, aad)
      pendingRouteMigration = pendingRouteMigration.updated(rs.forDID, aad)
      orderedRoutes.add(routes.size-1, rs)

    case m: RoutesMigrated =>
      m.routes.foreach { r =>
        migrationStatus += r -> RouteMigrationStatus(inProgress = false, migrated = true, recorded = true)
      }
      pendingRouteMigration = pendingRouteMigration -- m.routes
  }

  def handleGetRouteBatch(grd: GetRouteBatch): Unit = {
    logger.debug(s"ASC [$persistenceId] [ASCE->ARS] received GetRouteBatch: " + grd)
    val candidates = orderedRoutes.getRouteBatch(grd)
    val resp = GetRouteBatchResult(entityId, candidates.toSet)
    logger.debug(s"ASC [$persistenceId] sending response: " + resp)
    sender() ! resp
  }

  def handleGetRoute(gr: LegacyGetRoute): Unit = {
    val sndr = sender()
    val ri = routes.get(gr.forDID)
    logger.debug("get route result: " + ri)
    sndr ! ri
    ri.foreach { aad =>
      routeRegion ! ForIdentifier(gr.forDID, StoreRoute(aad))
    }
  }

  def migratePending(mp: MigratePending): Unit = {
    //max routes to be migrated with each of this function call would be `migrationBatchSize`

    if (! migrationStatus.exists(ms => ms._2.inProgress)) {
      val candidate = pendingRouteMigration.take(mp.batchSize)
      if (candidate.nonEmpty) {
        candidate.zipWithIndex.foreach { case (route, index) =>
          val routeId = route._1
          migrationStatus += routeId -> RouteMigrationStatus(inProgress = true, migrated = false, recorded = false)
          executeRouteMigration(routeId, route._2, mp.batchItemIntervalInMillis*index)(executionContext)
        }
      }
    }

    finishBatchProcessingIfCompleted()
  }

  def executeRouteMigration(routeId: RouteId,
                            aad: ActorAddressDetail,
                            afterDelayInMillis: Int)
                           (implicit executionContext: ExecutionContext): Unit = {
    if (afterDelayInMillis > 0) {
      val timeout = (afterDelayInMillis + Random.nextInt(100)).millis
      timers.startSingleTimer(routeId, FixRoute(routeId, aad), timeout)
    } else {
      fixRouting(routeId, aad)
    }
  }

  def fixRouting(routeId: RouteId, aad: ActorAddressDetail)
                (implicit executionContext: ExecutionContext): Unit = {
    agentMsgRouter
      .execute(InternalMsgRouteParam(routeId, SetMissingRoute))
      .map { _ =>
        self ! FinishRouteMigration(routeId)
      }.recover {
        case _: Throwable =>
          val fut = routeRegion ? ForIdentifier(routeId, StoreRoute(aad))
          fut.map { _ =>
            self ! FinishRouteMigration(routeId)
          }
    }
  }

  def finishRouteMigration(route: RouteId): Unit = {
    migrationStatus += route -> RouteMigrationStatus(inProgress = true, migrated = true, recorded = false)
    finishBatchProcessingIfCompleted()
  }

  def finishBatchProcessingIfCompleted(): Unit = {
    val inProgressRecords = migrationStatus.filter(ms => ms._2.inProgress)
    if (inProgressRecords.size == inProgressRecords.count(ms => ms._2.migrated)) {
      val notRecorded = migrationStatus.filter(_._2.notYetRecorded)
      if (notRecorded.nonEmpty) {
        writeAndApply(RoutesMigrated(notRecorded.keySet.toSeq))
        notRecorded.keySet.foreach { route =>
          migrationStatus += route -> RouteMigrationStatus(inProgress = false, migrated = true, recorded = true)
        }
        sendMigrationStatus()
      }
    }
    if (pendingRouteMigration.isEmpty) {
      sendMigrationStatus()
      stopAllScheduledJobs()
    }
  }

  def sendMigrationStatus(): Unit = {
    singletonParentProxyActor ! ForAgentRoutesMigrator(
      RecordMigrationStatus(entityId, migrationStatus.count(_._2.recorded)))
  }

  var routes: Map[RouteId, ActorAddressDetail] = Map.empty
  var orderedRoutes = new OrderedRoutes()

  var pendingRouteMigration: Map[RouteId, ActorAddressDetail] = Map.empty
  var migrationStatus: Map[RouteId, RouteMigrationStatus] = Map.empty

  override lazy val persistenceEncryptionKey: String = appConfig.getStringReq(ConfigConstants.SECRET_ROUTING_AGENT)

  val routeRegion: ActorRef = ClusterSharding(context.system).shardRegion(ROUTE_REGION_ACTOR_NAME)
  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)

}

class OrderedRoutes {
  private var routesByInsertionOrder: Map[Int, DidStr] = Map.empty

  def routes: List[DidStr] = routesByInsertionOrder.toSeq.sortBy(_._1).map(_._2).toList

  def add(index: Int, lrs: LegacyRouteSet): Unit = {
    routesByInsertionOrder += index -> lrs.forDID
  }

  def getRouteBatch(grd: GetRouteBatch): List[DidStr] = {
    (grd.fromIndex until grd.fromIndex + grd.batchSize).flatMap { index =>
      routesByInsertionOrder.get(index)
    }.toList
  }
}

object LegacyAgentRouteStore {
  def props(agentMsgRouter: AgentMsgRouter, executionContext: ExecutionContext)(implicit appConfig: AppConfig): Props =
    Props(new LegacyAgentRouteStore(agentMsgRouter, executionContext))
  val defaultPassivationTimeout = 600
}

//cmds
case class LegacySetRoute(forDID: DidStr, actorAddressDetail: ActorAddressDetail) extends ActorMessage
case class LegacyGetRoute(forDID: DidStr, oldBucketMapperVersions: Set[String] = RoutingAgentUtil.oldBucketMapperVersionIds)
  extends ActorMessage
case class GetRouteBatch(fromIndex: Int,
                         batchSize: Int) extends ActorMessage
case object GetRegisteredRouteSummary extends ActorMessage

//response msgs
case class GetRouteBatchResult(routeStoreEntityId: EntityId, dids: Set[DidStr]) extends ActorMessage

case class Status(totalCandidates: Int, processedRoutes: Int) extends ActorMessage

case class RouteMigrationStatus(inProgress: Boolean,
                                migrated: Boolean,
                                recorded: Boolean) extends ActorMessage {
  def notYetRecorded: Boolean = ! recorded
}

case object GetRouteStoreMigrationStatus extends ActorMessage
case class RouteStoreMigrationStatus(isJobScheduled: Boolean,
                                     totalCandidates: Int,
                                     currentStatus: Map[RouteId, RouteMigrationStatus]) extends ActorMessage

case class MigratePending(batchSize: Int, batchItemIntervalInMillis: Int) extends ActorMessage

case class FixRoute(routeId: RouteId, aad: ActorAddressDetail) extends ActorMessage
case class FinishRouteMigration(routeId: RouteId) extends ActorMessage