package com.evernym.verity.actor.agent.maintenance

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import akka.persistence.DeleteMessagesSuccess
import com.evernym.verity.actor.agent.msghandler.{ActorStateCleanupStatus, CheckActorStateCleanupState, FixActorState}
import com.evernym.verity.actor.agent.msgrouter._
import com.evernym.verity.actor.cluster_singleton.ForActorStateCleanupManager
import com.evernym.verity.actor.cluster_singleton.maintenance.Completed
import com.evernym.verity.actor.persistence.{BasePersistentActor, Done}
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, ForIdentifier}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.util.Util.getActorRefFromSelection
import com.typesafe.scalalogging.Logger


/**
 * updates legacy agent routes of one 'agent route store' actor
 * @param appConfig application config
 * @param agentMsgRouter agent msg router
 */
class ActorStateCleanupExecutor(val appConfig: AppConfig, val agentMsgRouter: AgentMsgRouter)
  extends BasePersistentActor {

  override def receiveCmd: Receive = {
    case GetExecutorStatus                      => sender ! CurrentStatus(routeStoreStatus, batchStatus)
    case prs: ProcessRouteStore                 => handleProcessRouteStore(prs)
    case crtbp: CandidateRoutesToBeProcessed    => processCandidateRoutes(crtbp.dids)
    case ascs: ActorStateCleanupStatus          => handleActorStateCleanupStatus(ascs)

    case ProcessPending if routeStoreStatus.isDefined  => processBatch()
    case ProcessPending if routeStoreStatus.isEmpty    => //nothing to do

    //below command is received from ActorStateCleanupManager as a response of 'Completed' sent by this actor
    case Done =>
    //below command is received from ActorStateCleanupManager once it has successfully recorded 'Completed'
    case Destroy => handleDestroy()
  }

  override def receiveEvent: Receive = {
    case su: StatusUpdated =>
      routeStoreStatus = Option(RouteStoreStatus(su.agentRouteStoreEntityId, su.totalRoutes, su.processedRoutes))
    case asc: ActorStateCleaned =>
      processedActorIds += asc.actorId
      routeStoreStatus = routeStoreStatus.map(s => s.copy(totalProcessed = s.totalProcessed + 1))
  }

  def handleActorStateCleaned(asc: ActorStateCleaned): Unit = {
    logger.debug(s"ASC [$persistenceId] [ASCE->ASCE]: received ActorStateCleaned: " + asc)
    writeAndApply(asc)
    batchStatus = batchStatus.copy(candidates = batchStatus.candidates ++ Map(asc.actorId -> true))
    if (batchStatus.isCompleted) {
      batchStatus = BatchStatus.empty
    }
  }

  def handleProcessRouteStore(prs: ProcessRouteStore): Unit = {
    logger.debug(s"ASC [$persistenceId] [ASCM->ASCE] received ProcessRouteStore: " + prs)
    if (routeStoreStatus.isDefined) {
      logger.debug(s"ASC [$persistenceId] status: " + routeStoreStatusReq)
      sender ! routeStoreStatusReq
    } else {
      val event = StatusUpdated(prs.agentRouteStoreEntityId, prs.totalRoutes)
      logger.debug(s"ASC [$persistenceId] recording event: " + event)
      writeApplyAndSendItBack(event)
    }
  }

  def processBatch(): Unit = {
    if (isActorStateCleanupEnabled) {
      logger.debug(s"ASC [$persistenceId] [ASCE->ASCE] process batch started")
      if (routeStoreStatusReq.isAllCompleted) { // all routes are processed
        logger.debug(s"ASC [$persistenceId] state cleanup completed for agent route store entity id: " + routeStoreStatusReq.agentRouteStoreEntityId)
        singletonParentProxyActor ! ForActorStateCleanupManager(Completed(entityId, routeStoreStatusReq.totalProcessed))
      } else if (batchStatus.isEmpty) { //no batch in progress
        logger.debug(s"ASC [$persistenceId] no batch is in progress: " + batchStatus)
        val cmd = GetRouteBatch(routeStoreStatusReq.totalCandidates, routeStoreStatusReq.totalProcessed, batchSize)
        agentRouteStoreRegion ! ForIdentifier(routeStoreStatusReq.agentRouteStoreEntityId, cmd)
      } else if (batchStatus.isInProgress) {
        batchStatus.candidates.filter(_._2 == false).keySet.foreach { did =>
          agentMsgRouter.forward(InternalMsgRouteParam(did, CheckActorStateCleanupState(did)), self)
        }
      }
    }
  }

  def handleDestroy(): Unit = {
    //once this actor is done with processing all 'routes' for given 'agent route store' actor
    //then it is no longer needed and should cleanup its persistence
    logger.debug(s"ASC [$persistenceId] [ASCM->ASCE] state cleanup completed for executor '$entityId', " +
      s"and this actor will be destroyed")
    processedActorIds = Set.empty
    routeStoreStatus = None
    batchStatus = BatchStatus.empty
    deleteMessages(lastSequenceNr)
  }

  override def handleDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit = {
    super.handleDeleteMsgSuccess(dms)
    stopActor()
  }

  /**
   * sends UpdateRoute command to agent actors (UserAgent and UserAgentPairwise)
   * which will then send 'SetRoute' with correct DID to AgentRouteStore
   *
   * @param candidateDIDs candidate DIDs
   */
  def processCandidateRoutes(candidateDIDs: Set[DID]): Unit = {
    if (routeStoreStatus.isDefined) {
      logger.debug(s"ASC [$persistenceId] [ARS->ASCE] received candidates to be processed: " + candidateDIDs.size)
      candidateDIDs.foreach { did =>
        logger.debug(s"ASC [$persistenceId] did: " + did)
        agentMsgRouter.execute(InternalMsgRouteParam(did, FixActorState))
        agentMsgRouter.forward(InternalMsgRouteParam(did, CheckActorStateCleanupState(did)), self)
      }
      if (candidateDIDs.nonEmpty) {
        if (batchStatus.isEmpty) {
          val updatedCandidates = batchStatus.candidates ++ candidateDIDs.map(_ -> false)
          batchStatus = batchStatus.copy(candidates = updatedCandidates)
        }
      } else {
        if (routeStoreStatusReq.isAllCompleted) {
          logger.debug(s"ASC [$persistenceId] received no candidates to be processed: " + routeStoreStatusReq)
          writeAndApply(StatusUpdated(routeStoreStatusReq.agentRouteStoreEntityId, routeStoreStatusReq.totalCandidates, routeStoreStatusReq.totalCandidates))
        } else {
          logger.warn(s"ASC [$persistenceId] (totalCandidates: ${routeStoreStatusReq.totalCandidates}, totalProcessed: ${routeStoreStatusReq.totalProcessed}) suspicious, but received 0")
        }
      }
    }
  }

  def handleActorStateCleanupStatus(ascs: ActorStateCleanupStatus): Unit = {
    if (routeStoreStatus.isDefined && ascs.isStateCleanedUp && ! processedActorIds.contains(ascs.forDID)) {
      handleActorStateCleaned(ActorStateCleaned(ascs.forDID))
    }
  }

  var processedActorIds: Set[String] = Set.empty
  var routeStoreStatus: Option[RouteStoreStatus] = None
  def routeStoreStatusReq: RouteStoreStatus = routeStoreStatus.getOrElse(
    throw new RuntimeException(s"ASC [$persistenceId] routeStoreStatus not yet initialized"))
  var batchStatus: BatchStatus = BatchStatus.empty

  override def persistenceEncryptionKey: String = this.getClass.getSimpleName

  def isActorStateCleanupEnabled: Boolean =
    appConfig
      .getConfigBooleanOption(CommonConfig.AGENT_ACTOR_STATE_CLEANUP_ENABLED)
      .getOrElse(false)

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

  private val logger: Logger = LoggingUtil.getLoggerByClass(classOf[ActorStateCleanupExecutor])
}

//status of a route store entity candidates
case class RouteStoreStatus(agentRouteStoreEntityId: EntityId,
                            totalCandidates: Int,
                            totalProcessed: Int) extends ActorMessageClass {
  def isAllCompleted: Boolean = totalCandidates == totalProcessed
}

//incoming message
case object GetExecutorStatus extends ActorMessageObject
case class ProcessRouteStore(agentRouteStoreEntityId: String, totalRoutes: Int, processedRoutes: Int = 0) extends ActorMessageClass
case object Destroy extends ActorMessageObject

object ActorStateCleanupExecutor {
  def props(appConfig: AppConfig, agentMsgRouter: AgentMsgRouter): Props =
    Props(new ActorStateCleanupExecutor(appConfig, agentMsgRouter))
}

case object ProcessPending extends ActorMessageObject

object BatchStatus {
  def empty: BatchStatus = BatchStatus(Map.empty)
}
case class BatchStatus(candidates: Map[DID, Boolean]) {
  def isInProgress: Boolean = candidates.exists(_._2 == false)
  def isCompleted: Boolean = candidates.nonEmpty && candidates.forall(_._2 == true)
  def isEmpty: Boolean = candidates.isEmpty
}

case class CurrentStatus(routeStoreStatus: Option[RouteStoreStatus], batchStatus: BatchStatus) extends ActorMessageClass