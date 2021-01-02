package com.evernym.verity.actor.agent.msgrouter

import akka.pattern.ask
import akka.event.LoggingReceive
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, RouteMigrated, RouteSet}

import scala.concurrent.Future

/**
 * Migration is a feature that's not used today, but was added because we
 * anticipated a time when a sharding strategy would need to be updated.
 */
trait HasBucketMigrationSupport { this: AgentRouteStore =>

  val receiveMigrationCmd: Receive = LoggingReceive.withLabel("receiveMigrationCmd") {
    case mcr: MigrateCandidateRoutes =>
      logProgressMsgWithDebugLevel("about to start migrating candidate routes...")
      Future(migrateCandidateRoutes(mcr))

    case mr: MigrateRoute => handleMigrateRoute(mr)

    case rm: RouteMigrated => writeAndApply(rm)
      logProgressMsgWithDebugLevel("route migrated: " + rm)
  }

  val receiveMigrationEvent: Receive = {
    case rim: RouteMigrated => migratedDIDs = migratedDIDs ++ Set(rim.forDID)
  }

  def handleMigrateRoute(mr: MigrateRoute): Unit = {
    logProgressMsgWithDebugLevel("migrate route received: " + mr)
    routes.get(mr.forDID).foreach { aad =>
      val futResp = routingAgentRegion ? ForIdentifier(mr.toBucketPersistenceId, SetRoute(mr.forDID, aad))
      futResp map {
        case _ @ (_: RouteSet | _:RouteAlreadySet) => self ! RouteMigrated(mr.forDID, mr.toBucketPersistenceId)
        case _ => logProgressMsgWithErrorLevel("error occurred during route migration: " + mr)
      }
    }
  }

  def migrateCandidateRoutes(mcr: MigrateCandidateRoutes): Unit = {
    val routesToBeMigrated = routes.filterNot(r => migratedDIDs.contains(r._1))
    if (routesToBeMigrated.isEmpty) {
      logProgressMsgWithDebugLevel("no more records to be migrated, cancelling the scheduled job")
      migrationScheduledJob.foreach(stopScheduledJob)
    } else {
      logProgressMsgWithDebugLevel("total candidate routes for migration: " + routesToBeMigrated.size + ", " +
        "will start migrating records in chunks...")
      routesToBeMigrated.take(3).foreach(r => self ! MigrateRoute(r._1, mcr.toBucketPersistenceId))
    }
  }

  override def postActorRecoveryCompleted(): List[Future[Any]] = {
    List(Future(schedulePeriodJobIfReq()))
  }

  def schedulePeriodJobIfReq(): Unit = {
    getMigrateRouteTargetBucketPersistenceId match {
      case Some(latestBucketPersistenceId) =>
        logProgressMsgWithDebugLevel("scheduling periodic job for route migration")
        migrationScheduledJob = {
          val jobId = "MigrateCandidateRoutes"
          scheduleJob(
            jobId,
            RoutingAgentUtil.getRandomIntervalInSecondsForMigrationJob,
            MigrateCandidateRoutes(latestBucketPersistenceId)
          )
          Option(jobId)
        }
      case None =>
    }
  }


  def getMigrateRouteTargetBucketPersistenceId: Option[String] = {
    routes.headOption match {
      case Some(hr) =>
        val latestBucketPersistenceId = RoutingAgentUtil.getBucketEntityId(hr._1)
        logProgressMsgWithDebugLevel("DID: " + hr._1 + ", entityId: " + entityId + ", " +
          "latestBucketPersistenceId: " + latestBucketPersistenceId)

        if (entityId != latestBucketPersistenceId) {
          logProgressMsgWithDebugLevel("migration needed, will start migration in chunks...")
          Some(latestBucketPersistenceId)
        } else {
          logProgressMsgWithDebugLevel("migration NOT needed")
          None
        }
      case None => None
    }
  }

  def logProgressMsgWithDebugLevel(msg: String): Unit = {
    logger.debug(s"ROUTE MIGRATION: $msg", ("entity_id", entityId))
  }

  def logProgressMsgWithErrorLevel(msg: String): Unit = {
    logger.error(s"ROUTE MIGRATION: $msg", ("entity_id", entityId))
  }

  var migratedDIDs: Set[DID] = Set.empty
  var migrationScheduledJob: Option[String] = None
}

case class MigrateCandidateRoutes(toBucketPersistenceId: String) extends ActorMessage
case class MigrateRoute(forDID: DID, toBucketPersistenceId: String) extends ActorMessage
