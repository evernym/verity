package com.evernym.verity.actor.agent.maintenance

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import com.evernym.verity.actor.agent.msghandler.{ActorStateCleanupStatus, FixActorState}
import com.evernym.verity.actor.agent.msgrouter.{GetRouteBatchResult, _}
import com.evernym.verity.actor.cluster_singleton.ForActorStateCleanupManager
import com.evernym.verity.actor.persistence.{BasePersistentActor, Done, Stop}
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, ActorStateCleaned, ActorStateStored, BatchSizeRecorded, Completed, ForIdentifier, StatusUpdated}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.util.Util.getActorRefFromSelection


/**
 * updates legacy agent routes of one 'agent route store' actor
 * @param appConfig application config
 * @param agentMsgRouter agent msg router
 */
class ActorStateCleanupExecutor(val appConfig: AppConfig, val agentMsgRouter: AgentMsgRouter)
  extends BasePersistentActor
    with ActorStateCleanupBase {

  override def receiveCmd: Receive = {
    case ges: GetExecutorStatus             => handleGetStatus(ges)
    case prs: ProcessRouteStore             => handleProcessRouteStore(prs)
    case grbr: GetRouteBatchResult          => handleGetRouteBatchResult(grbr)
    case ias: InitialActorState             => handleInitialActorState(ias)
    case ascs: ActorStateCleanupStatus      => handleActorStateCleanupStatus(ascs)

    case ProcessPending if routeStoreStatus.isDefined  => processBatch()
    case ProcessPending if routeStoreStatus.isEmpty    => //nothing to do

    //below command is received from ActorStateCleanupManager as a response of 'Completed' sent by this actor
    case Done =>
    //below command is received from ActorStateCleanupManager once it has successfully recorded 'Completed'
    case Destroy => handleDestroy()
  }

  override def receiveEvent: Receive = {
    case bsr: BatchSizeRecorded =>
      recordedBatchSize = BatchSize(bsr.previousBatchSize, bsr.newBatchSize)

    case su: StatusUpdated =>
      routeStoreStatus = Option(RouteStoreStatus(su.agentRouteStoreEntityId, su.totalRoutes, su.processedRoutes))

    case ass: ActorStateStored =>
      agentActorCleanupState += (ass.actorId -> CleanupStatus(isRouteSet=false, ass.threadContexts, ass.threadContexts, 0, 0))

    case asc: ActorStateCleaned =>
      agentActorCleanupState.get(asc.actorId).foreach { aacs =>
        agentActorCleanupState += (asc.actorId -> aacs.copy(
          isRouteSet = true,
          pendingCount = 0,
          successfullyMigratedCount = asc.successfullyMigratedCount,
          nonMigratedCount = asc.nonMigratedCount))
        routeStoreStatus = routeStoreStatus.map(s => s.copy(totalProcessed = s.totalProcessed + 1))
      }
  }

  def handleGetStatus(ges: GetExecutorStatus): Unit = {
    val cs = ExecutorStatus(routeStoreStatus, batchStatus)
    if (ges.includeDetails) {
      sender ! cs.copy(actorStateCleanupStatus = Option(agentActorCleanupState))
    } else {
      sender ! cs
    }
  }

  def handleInitialActorState(ias: InitialActorState): Unit = {
    if (! agentActorCleanupState.contains(ias.actorId)) {
      val event = ActorStateStored(ias.actorId, ias.threadContexts)
      applyEvent(event)
      writeWithoutApply(event)
      if (ias.threadContexts == 0 && ias.isRouteSet)
        onActorStateCleanup(ias.actorId, 0, 0)
    }
  }

  def handleActorStateCleaned(asc: ActorStateCleaned): Unit = {
    logger.debug(s"ASC [$persistenceId] [ASCE->ASCE]: received ActorStateCleaned: " + asc)
    if (agentActorCleanupState.contains(asc.actorId)) {
      writeAndApply(asc)
      batchStatus = batchStatus.copy(candidates = batchStatus.candidates ++ Map(asc.actorId -> true))
      if (batchStatus.isCompleted) {
        batchStatus = BatchStatus.empty
        if (recordedBatchSize.last != recordedBatchSize.current) {
          val event = BatchSizeRecorded(batchSize, batchSize)
          applyEvent(event)
          writeWithoutApply(event)
        }
        sendProcessPending()
      }
    } else {
      logger.error(s"unexpected situation, received asc: $asc, without an initial state")
    }
  }

  def handleProcessRouteStore(prs: ProcessRouteStore): Unit = {
    logger.debug(s"ASC [$persistenceId] [ASCM->ASCE] received ProcessRouteStore: " + prs)
    if (routeStoreStatus.isDefined) {
      logger.debug(s"ASC [$persistenceId] status: " + routeStoreStatusReq)
      sender ! routeStoreStatusExtended
    } else {
      val event = StatusUpdated(prs.agentRouteStoreEntityId, prs.totalRoutes)
      logger.debug(s"ASC [$persistenceId] recording event: " + event)
      writeApplyAndSendItBack(event)
    }
    actorStateCleanupManager = Option(sender)
    sendProcessPending()
  }

  def isAllActorStateCleanedUp: Boolean =
    routeStoreStatusReq.isAllCompleted &&
      routeStoreStatusReq.totalCandidates == agentActorCleanupState.size

  def recordBatchSizeIfChanged(): Unit = {
    val eventOpt = if (recordedBatchSize.last == -1) {
      Option(BatchSizeRecorded(batchSize, batchSize))
    } else if (recordedBatchSize.current != batchSize) {
      Option(BatchSizeRecorded(recordedBatchSize.current, batchSize))
    } else None
    eventOpt.foreach { event =>
      applyEvent(event)
      writeWithoutApply(event)
    }
  }

  def processBatch(): Unit = {
    if (isActorStateCleanupEnabled) {
      recordBatchSizeIfChanged()
      logger.debug(s"ASC [$persistenceId] [ASCE->ASCE] process batch started")
      if (isAllActorStateCleanedUp) { // all routes are processed
        logger.debug(s"ASC [$persistenceId] state cleanup completed for agent route store entity id: " + routeStoreStatusReq.agentRouteStoreEntityId)
        singletonParentProxyActor ! ForActorStateCleanupManager(Completed(entityId, routeStoreStatusReq.totalProcessed))
        stopActor()
      } else if (batchStatus.isEmpty) { //no batch in progress
        logger.debug(s"ASC [$persistenceId] no batch is in progress: " + batchStatus)
        sendGetNextRouteBatchCmd()
      } else if (batchStatus.isInProgress) {
        batchStatus.candidates.filter(_._2 == false).keySet.foreach { did =>
          sendFixActorStateCleanupCmd(did)
        }
      }
    }
  }

  def sendFixActorStateCleanupCmd(did: DID): Unit = {
    agentMsgRouter.forward(InternalMsgRouteParam(did, FixActorState(did, self)), self)
  }

  def sendGetNextRouteBatchCmd(): Unit = {
    val batchSizeToBeUsed = if (recordedBatchSize.last != recordedBatchSize.current) {
      recordedBatchSize.last
    } else recordedBatchSize.current
    val fromIndex = routeStoreStatusReq.totalProcessed%batchSizeToBeUsed match {
      case 0 => routeStoreStatusReq.totalProcessed
      case _ => (routeStoreStatusReq.totalProcessed/batchSizeToBeUsed)*batchSizeToBeUsed
    }
    val cmd = GetRouteBatch(routeStoreStatusReq.totalCandidates, fromIndex, batchSizeToBeUsed)
    agentRouteStoreRegion ! ForIdentifier(routeStoreStatusReq.agentRouteStoreEntityId, cmd)
  }

  def handleDestroy(): Unit = {
    //once this actor is done with processing all 'routes' for given 'agent route store' actor
    //then it is no longer needed and should cleanup its persistence
    logger.debug(s"ASC [$persistenceId] [ASCM->ASCE] state cleanup completed for executor '$entityId', " +
      s"and this actor will be destroyed")
    deleteMessagesExtended(lastSequenceNr)
  }

  def sendProcessPending(): Unit = self ! ProcessPending

  override def postAllMsgsDeleted(): Unit = {
    singletonParentProxyActor ! ForActorStateCleanupManager(Destroyed(entityId))
    agentActorCleanupState = Map.empty
    routeStoreStatus = None
    recordedBatchSize = BatchSize(-1, -1)
    batchStatus = BatchStatus.empty
    stopActor()
  }

  /**
   * agent actor's whose state has been cleaned
   * @return
   */
  def processedActorIds: Set[EntityId] = agentActorCleanupState.filter(_._2.actorStateCleaned == true).keySet

  /**
   * sends UpdateRoute command to agent actors (UserAgent and UserAgentPairwise)
   * which will then send 'SetRoute' with correct DID to AgentRouteStore
   *
   * @param grbr candidate DIDs
   */
  def handleGetRouteBatchResult(grbr: GetRouteBatchResult): Unit = {
    val candidateDIDs = grbr.dids
    logger.debug(s"ASC [$persistenceId] [ARS->ASCE] received candidates to be processed: " + candidateDIDs.size)
    val targetCandidateDIDs = candidateDIDs -- processedActorIds
    if (routeStoreStatus.isDefined) {
      targetCandidateDIDs.foreach { did =>
        logger.debug(s"ASC [$persistenceId] did: " + did)
        sendFixActorStateCleanupCmd(did)
      }
      if (targetCandidateDIDs.nonEmpty) {
        if (batchStatus.isEmpty) {
          batchStatus = BatchStatus(targetCandidateDIDs.map(_ -> false).toMap)
        }
      } else {
        if (routeStoreStatusReq.isAllCompleted) {
          logger.debug(s"ASC [$persistenceId] received no candidates to be processed: " + routeStoreStatusReq)
          writeAndApply(StatusUpdated(routeStoreStatusReq.agentRouteStoreEntityId,
            routeStoreStatusReq.totalCandidates, routeStoreStatusReq.totalCandidates))
        } else {
          logger.warn(s"ASC [$persistenceId] (totalCandidates: ${routeStoreStatusReq.totalCandidates}, " +
            s"totalProcessed: ${routeStoreStatusReq.totalProcessed}) suspicious, " +
            s"expected candidates but received 0")
        }
      }
    }
  }

  def handleActorStateCleanupStatus(ascs: ActorStateCleanupStatus): Unit = {
    agentActorCleanupState.get(ascs.actorDID).foreach { acs =>
      if (routeStoreStatus.isDefined && ascs.isRouteFixed && ! acs.actorStateCleaned) {
        val updatedStatus = acs.copy(
          isRouteSet = ascs.isRouteFixed,
          pendingCount = ascs.pendingCount,
          successfullyMigratedCount = ascs.successfullyMigratedCount,
          nonMigratedCount = ascs.nonMigratedCount
        )
        agentActorCleanupState += ascs.actorDID -> updatedStatus

        if (acs.totalThreadContexts == updatedStatus.totalProcessed) {
          onActorStateCleanup(ascs.actorDID, ascs.successfullyMigratedCount, ascs.nonMigratedCount)
        }

        actorStateCleanupManager.foreach(_ ! routeStoreStatusExtended)
      }
    }
  }

  def onActorStateCleanup(actorDID: DID, successfullyMigrated: Int, nonMigrated: Int): Unit = {
    //agentMsgRouter.execute(InternalMsgRouteParam(actorDID, Stop()))   //stop agent actor
    handleActorStateCleaned(ActorStateCleaned(actorDID, successfullyMigrated, nonMigrated))
  }

  var actorStateCleanupManager: Option[ActorRef] = None
  var agentActorCleanupState: Map[DID, CleanupStatus] = Map.empty
  var routeStoreStatus: Option[RouteStoreStatus] = None
  var recordedBatchSize: BatchSize = BatchSize(-1, -1)
  var batchStatus: BatchStatus = BatchStatus.empty

  def routeStoreStatusReq: RouteStoreStatus = routeStoreStatus.getOrElse(
    throw new RuntimeException(s"ASC [$persistenceId] routeStoreStatus not yet initialized"))

  def routeStoreStatusExtended: RouteStoreStatus = {
    val inProgressCleanupStatus = agentActorCleanupState.filter(_._2.actorStateCleaned == false)
    routeStoreStatusReq.copy(inProgressCleanupStatus = inProgressCleanupStatus)
  }

  lazy val batchSize: Int =
    appConfig.getConfigIntOption(CommonConfig.AAS_CLEANUP_EXECUTOR_BATCH_SIZE)
    .getOrElse(5)

  val agentRouteStoreRegion: ActorRef = ClusterSharding.get(context.system).shardRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME)

  lazy val singletonParentProxyActor: ActorRef =
    getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)

  lazy val scheduledJobInitialDelay: Int =
    appConfig
      .getConfigIntOption(AAS_CLEANUP_EXECUTOR_SCHEDULED_JOB_INITIAL_DELAY_IN_SECONDS)
      .getOrElse(60)

  lazy val scheduledJobInterval: Int =
    appConfig
      .getConfigIntOption(AAS_CLEANUP_EXECUTOR_SCHEDULED_JOB_INTERVAL_IN_SECONDS)
      .getOrElse(300)

  scheduleJob("periodic_job", scheduledJobInitialDelay, scheduledJobInterval, ProcessPending)

}

//status of a route store entity candidates
case class RouteStoreStatus(agentRouteStoreEntityId: EntityId,
                            totalCandidates: Int,
                            totalProcessed: Int,
                            inProgressCleanupStatus: Map[DID, CleanupStatus] = Map.empty) extends ActorMessageClass {
  def isAllCompleted: Boolean = totalCandidates == totalProcessed
}

case class CleanupStatus(isRouteSet: Boolean,
                         totalThreadContexts: Int,
                         pendingCount: Int,
                         successfullyMigratedCount: Int,
                         nonMigratedCount: Int) {
  def totalProcessed: Int = successfullyMigratedCount + nonMigratedCount
  def actorStateCleaned: Boolean = isRouteSet && (totalThreadContexts == totalProcessed)
}

//incoming message
case object Destroy extends ActorMessageObject
case class GetExecutorStatus(includeDetails: Boolean = false) extends ActorMessageClass
case class ProcessRouteStore(agentRouteStoreEntityId: EntityId, totalRoutes: Int) extends ActorMessageClass

object ActorStateCleanupExecutor {
  def props(appConfig: AppConfig, agentMsgRouter: AgentMsgRouter): Props =
    Props(new ActorStateCleanupExecutor(appConfig, agentMsgRouter))
}

object BatchStatus {
  def empty: BatchStatus = BatchStatus(Map.empty)
}
case class BatchStatus(candidates: Map[DID, Boolean]) {
  def isInProgress: Boolean = candidates.exists(_._2 == false)
  def isCompleted: Boolean = candidates.nonEmpty && candidates.forall(_._2 == true)
  def isEmpty: Boolean = candidates.isEmpty
}

case class ExecutorStatus(routeStoreStatus: Option[RouteStoreStatus],
                          batchStatus: BatchStatus,
                          actorStateCleanupStatus: Option[Map[DID, CleanupStatus]]=None) extends ActorMessageClass

case class Destroyed(entityId: EntityId) extends ActorMessageClass

case class InitialActorState(actorId: DID, isRouteSet: Boolean, threadContexts: Int) extends ActorMessageClass

case class BatchSize(last: Int, current: Int)