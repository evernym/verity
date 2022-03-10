package com.evernym.verity.actor.cluster_singleton.maintenance

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import com.evernym.verity.actor.agent.maintenance.RegisteredRouteSummary
import com.evernym.verity.actor.agent.msgrouter.RoutingAgentBucketMapperV1
import com.evernym.verity.actor.agent.msgrouter.legacy.{GetRegisteredRouteSummary, MigratePending}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.persistence.{BasePersistentTimeoutActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, MigrationCandidatesRecorded, MigrationStatusRecorded}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.ActorNameConstants.{AGENT_ROUTES_MIGRATOR, LEGACY_AGENT_ROUTE_STORE_REGION_ACTOR_NAME, SINGLETON_PARENT_PROXY}
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.util.Util.getActorRefFromSelection

import scala.concurrent.ExecutionContext


class AgentRoutesMigrator(val appConfig: AppConfig, executionContext: ExecutionContext)
  extends BasePersistentTimeoutActor
  with DefaultPersistenceEncryption {

  override def futureExecutionContext: ExecutionContext = executionContext

  override def receiveCmd: Receive = receiveMain orElse receiveOther

  def receiveMain: Receive = {
    case RunMigration                   => runMigration()
    case rrs: RegisteredRouteSummary    => recordCandidateStatus(rrs)
    case rms: RecordMigrationStatus     => recordMigrationStatus(rms)
  }

  def receiveOther: Receive = {
    case gms: GetMigrationStatus        => sendMigrationStatus(gms)
    case StopJob                        => handleStopJob()
    case StartJob                       => handleStartJob()
    case Reset                          => handleReset()
  }

  override def receiveEvent: Receive = {
    case mcr: MigrationCandidatesRecorded =>
      status += mcr.entityId -> MigrationStatus(mcr.totalCandidates, 0)

    case msr: MigrationStatusRecorded =>
      status.get(msr.entityId).foreach { cms =>
        status += msr.entityId -> cms.copy(totalMigrated = msr.totalMigrated)
      }
  }

  def runMigration(): Unit = {
    //max parallel legacy agent route store to be asked for
    // migration would be equal to 'routeStoreBatchSize'
    if (migrationEnabled) {
      if (isAllMigrationCompleted) {
        stopAllScheduledJobs()
      } else if (status.size != totalLegacyRouteActors) {
        performPendingRegistration()
      } else {
        processPendingMigration()
      }
    }
  }

  def recordCandidateStatus(rrs: RegisteredRouteSummary): Unit = {
    if (! status.contains(rrs.entityId)) {
      writeAndApply(MigrationCandidatesRecorded(rrs.entityId, rrs.totalCandidateRoutes))
      if (rrs.totalCandidateRoutes == 0) {
        writeAndApply(MigrationStatusRecorded(rrs.entityId, 0))
      }
    }
  }

  def recordMigrationStatus(rms: RecordMigrationStatus): Unit = {
    status.get(rms.entityId).foreach { ms =>
      val isAllMigrated = ! ms.isMigrationCompleted && ms.totalCandidates == rms.totalMigrated
      val isOkToRecordCurrentProgress = ms.shallRecordCurrentProgress(rms.totalMigrated)
      if (isAllMigrated || isOkToRecordCurrentProgress) {
        writeAndApply(MigrationStatusRecorded(rms.entityId, rms.totalMigrated))
      } else {
        status += rms.entityId -> ms.copy(totalMigrated = rms.totalMigrated)
      }
    }
  }

  def sendMigrationStatus(gms: GetMigrationStatus): Unit = {
    val totalRoutesRegistered = status.size
    val totalCandidates = status.map(_._2.totalCandidates).sum
    val registered = RouteSummary(totalRoutesRegistered, totalCandidates)

    val completed = {
      val completedStatus = status.filter(_._2.isMigrationCompleted)
      RouteSummary(
        completedStatus.size,
        completedStatus.map(_._2.totalCandidates).sum,
        Option(completedStatus.map(_._2.totalMigrated).sum)
      )
    }

    val inProgress = {
      val inProgressStatus = status.filter(_._2.isMigrationInProgress)
      RouteSummary(
        inProgressStatus.size,
        inProgressStatus.map(_._2.totalCandidates).sum,
        Option(inProgressStatus.map(_._2.totalMigrated).sum))
    }

    val detail = if (gms.withDetail) Option(status) else None
    sender() ! MigrationStatusDetail(
      timers.isTimerActive("migrate"),
      registered, completed, inProgress, detail
    )
  }

  def performPendingRegistration(): Unit  = {
    val pendingEntityIds = allLegacyAgentRouteStoreEntityIds.flatMap { entityId =>
      if (! status.keySet.contains(entityId)) {
        Option(entityId)
      } else None
    }
    pendingEntityIds.take(registrationBatchSize).foreach { entityId =>
      legacyAgentRouteStoreRegion ! ForIdentifier(entityId, GetRegisteredRouteSummary)
    }
  }

  def processPendingMigration(): Unit  = {
    val inProgress = status.filter(ms => ms._2.isMigrationPending).take(processingBatchSize)
    inProgress.keySet.foreach(sendMigratePending)
  }

  def sendMigratePending(entityId: EntityId): Unit = {
    legacyAgentRouteStoreRegion ! ForIdentifier(entityId,
      MigratePending(routesBatchSize, routesBatchItemIntervalInMillis))
  }

  def handleReset(): Unit = {
    stopAllScheduledJobs()
    deleteMessagesExtended(lastSequenceNr, Option(receiveOther))
    sender() ! Done
  }

  def handleStartJob(): Unit = {
    scheduleJob("migrate", scheduledJobInterval, RunMigration)
    sender() ! Done
  }

  def handleStopJob(): Unit = {
    stopAllScheduledJobs()
    sender() ! Done
  }

  override def postAllMsgsDeleted(): Unit = {
    status = Map.empty
  }

  val totalLegacyRouteActors = 100

  var allLegacyAgentRouteStoreEntityIds: Seq[String] =
    (0 until totalLegacyRouteActors)
      .map(bucketId =>RoutingAgentBucketMapperV1.entityIdByBucketId(bucketId))

  var status: Map[EntityId, MigrationStatus] = Map.empty

  def isAllMigrationCompleted: Boolean = {
    status.size == allLegacyAgentRouteStoreEntityIds.size &&
      status.forall(ms => ms._2.totalCandidates == ms._2.totalMigrated)
  }

  lazy val singletonParentProxyActor: ActorRef =
    getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)

  lazy val scheduledJobInterval: Int =
    appConfig
      .getIntOption(AGENT_ROUTES_MIGRATOR_SCHEDULED_JOB_INTERVAL_IN_SECONDS)
      .getOrElse(300)

  lazy val migrationEnabled: Boolean =
    appConfig
      .getBooleanOption(AGENT_ROUTES_MIGRATOR_ENABLED)
      .getOrElse(false)

  //how many parallel "legacy agent route store actor" to ask for registration
  lazy val registrationBatchSize: Int =
    appConfig
      .getIntOption(AGENT_ROUTES_MIGRATOR_REGISTRATION_BATCH_SIZE)
      .getOrElse(5)

  //how many parallel "legacy agent route store actor" to be processed for migration
  lazy val processingBatchSize: Int =
    appConfig
      .getIntOption(AGENT_ROUTES_MIGRATOR_PROCESSING_BATCH_SIZE)
      .getOrElse(2)

  //how many parallel routes "per legacy agent route actor" to be migrated
  lazy val routesBatchSize: Int =
    appConfig
      .getIntOption(AGENT_ROUTES_MIGRATOR_ROUTES_BATCH_SIZE)
      .getOrElse(5)

  lazy val routesBatchItemIntervalInMillis: Int =
    appConfig
      .getIntOption(AGENT_ROUTES_MIGRATOR_ROUTES_BATCH_ITEM_INTERVAL_IN_MILLIS)
      .getOrElse(0)

  scheduleJob("migrate", scheduledJobInterval, RunMigration)

  lazy val legacyAgentRouteStoreRegion: ActorRef =
    ClusterSharding.get(context.system)
      .shardRegion(LEGACY_AGENT_ROUTE_STORE_REGION_ACTOR_NAME)
}

object AgentRoutesMigrator {
  val name: String = AGENT_ROUTES_MIGRATOR
  def props(appConfig: AppConfig, executionContext: ExecutionContext): Props =
    Props(
      new AgentRoutesMigrator(
        appConfig,
        executionContext
      )
    )
}

object GetMigrationStatus {
  def apply(detail: Option[String]): GetMigrationStatus =
    GetMigrationStatus(detail.contains(YES))
}
case class GetMigrationStatus(withDetail: Boolean) extends ActorMessage
case class RecordMigrationStatus(entityId: EntityId, totalMigrated: Int) extends ActorMessage

case class MigrationStatus(totalCandidates: Int, totalMigrated: Int) extends ActorMessage {
  def isMigrationPending: Boolean = totalCandidates > 0 && totalCandidates != totalMigrated
  def isMigrationCompleted: Boolean = totalCandidates == totalMigrated
  def isMigrationInProgress: Boolean = isMigrationPending && totalMigrated > 0

  def recordBatchSize: Int = totalCandidates/10   //TODO: is this record batch size ok?
  def shallRecordCurrentProgress(totalMigrated: Int): Boolean = {
    totalMigrated == totalMigrated + recordBatchSize
  }
}

case class RouteSummary(totalRouteStores: Int, totalCandidates: Int, totalProcessed: Option[Int]=None)

case class MigrationStatusDetail(isJobScheduled: Boolean,
                                 registered: RouteSummary,
                                 completed: RouteSummary,
                                 inProgress: RouteSummary,
                                 status: Option[Map[EntityId, MigrationStatus]] = None) extends ActorMessage

case object RunMigration extends ActorMessage
case object Migrate extends ActorMessage

case object Reset extends ActorMessage
case object StartJob extends ActorMessage
case object StopJob extends ActorMessage