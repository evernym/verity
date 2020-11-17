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
    case GetUpdaterStatus                       => sender ! CurrentStatus(statusOpt, batchStatus)
    case prs: ProcessRouteStore                 => handleProcessRouteStore(prs)
    case crtbp: CandidateRoutesToBeProcessed    => processCandidateRoutes(crtbp.dids)
    case ascs: ActorStateCleanupStatus          => handleActorStateCleanupStatus(ascs)

    case ProcessPending if statusOpt.isDefined  => processBatch()
    case ProcessPending if statusOpt.isEmpty    => //nothing to do

    //below command is received from ActorStateCleanupManager as a response of 'Completed' sent by this actor
    case Done =>
    //below command is received from ActorStateCleanupManager once it has successfully recorded 'Completed'
    case Destroy => handleDestroy()
  }

  override def receiveEvent: Receive = {
    case su: StatusUpdated =>
      statusOpt = Option(ProcessingStatus(su.agentRouteStoreEntityId, su.totalRoutes, su.processedRoutes))
    case _: ActorStateCleaned =>
      statusOpt = statusOpt.map(s => s.copy(totalProcessed = s.totalProcessed + 1))
  }

  def handleRouteProcessed(asc: ActorStateCleaned): Unit = {
    logger.debug(s"ASC [$persistenceId] [ASCE->ASCE]: received RouteProcessed: " + asc)
    writeAndApply(asc)
    batchStatus = batchStatus.copy(processed = batchStatus.processed + 1)
    if (batchStatus.isCompleted) {
      batchStatus = BatchProcessingStatus.empty
    }
  }

  def handleProcessRouteStore(prs: ProcessRouteStore): Unit = {
    logger.debug(s"ASC [$persistenceId] [ASCM->ASCE] received ProcessRouteStore: " + prs)
    if (statusOpt.isDefined) {
      logger.debug(s"ASC [$persistenceId] status: " + status)
      sender ! status
    } else {
      val event = StatusUpdated(prs.agentRouteStoreEntityId, prs.totalRoutes)
      logger.debug(s"ASC [$persistenceId] recording event: " + event)
      writeApplyAndSendItBack(event)
    }
  }

  def processBatch(): Unit = {
    if (isActorStateCleanupEnabled) {
      logger.debug(s"ASC [$persistenceId] [ASCE->ASCE] process batch started")
      if (status.isAllCompleted) { // all routes are processed
        logger.debug(s"ASC [$persistenceId] state cleanup completed for agent route store entity id: " + status.agentRouteStoreEntityId)
        singletonParentProxyActor ! ForActorStateCleanupManager(Completed(entityId, status.totalProcessed))
      } else if (batchStatus.isEmpty) { //no batch in progress
        logger.debug(s"ASC [$persistenceId] no batch is in progress: " + batchStatus)
        val cmd = GetRouteBatch(status.totalCandidates, status.totalProcessed, batchSize)
        agentRouteStoreRegion ! ForIdentifier(status.agentRouteStoreEntityId, cmd)
      } else if (batchStatus.isInProgress) {
        batchStatus.candidateDIDs.foreach { did =>
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
    deleteMessages(lastSequenceNr)
  }

  override def handleDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit = {
    stopActor()
    super.handleDeleteMsgSuccess(dms)
  }

  /**
   * sends UpdateRoute command to agent actors (UserAgent and UserAgentPairwise)
   * which will then send 'SetRoute' with correct DID to AgentRouteStore
   *
   * @param candidateDIDs candidate DIDs
   */
  def processCandidateRoutes(candidateDIDs: Set[DID]): Unit = {
    logger.debug(s"ASC [$persistenceId] [ARS->ASCE] received candidates to be processed: " + candidateDIDs.size)
    candidateDIDs.foreach { did =>
      logger.debug(s"ASC [$persistenceId] did: " + did)
      agentMsgRouter.execute(InternalMsgRouteParam(did, FixActorState))
      agentMsgRouter.forward(InternalMsgRouteParam(did, CheckActorStateCleanupState(did)), self)
    }
    if (candidateDIDs.nonEmpty) {
      batchStatus = batchStatus.copy(candidateDIDs = candidateDIDs, 0)
    } else {
      if (status.totalCandidates > 0) {
        logger.warn(s"ASC [$persistenceId] suspicious, totalCandidates: ${status.totalCandidates}, but received 0")
      } else {
        logger.debug(s"ASC [$persistenceId] received no candidates to be processed: " + status)
        writeAndApply(StatusUpdated(status.agentRouteStoreEntityId, status.totalCandidates, status.totalCandidates))
      }
    }
  }

  def handleActorStateCleanupStatus(ascs: ActorStateCleanupStatus): Unit = {
    if (ascs.routeFixed && ascs.threadContextMigrated) {
      handleRouteProcessed(ActorStateCleaned(ascs.forDID))
    }
  }

  var statusOpt: Option[ProcessingStatus] = None
  def status: ProcessingStatus = statusOpt.getOrElse(throw new RuntimeException(s"ASC [$persistenceId] status not yet initialized"))
  var batchStatus: BatchProcessingStatus = BatchProcessingStatus.empty

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

//status
case class ProcessingStatus(agentRouteStoreEntityId: EntityId, totalCandidates: Int, totalProcessed: Int) extends ActorMessageClass {
  def isAllCompleted: Boolean = totalCandidates == totalProcessed
}

//incoming message
case object GetUpdaterStatus extends ActorMessageObject
case class ProcessRouteStore(agentRouteStoreEntityId: String, totalRoutes: Int, processedRoutes: Int = 0) extends ActorMessageClass
case object Destroy extends ActorMessageObject

object ActorStateCleanupExecutor {
  def props(appConfig: AppConfig, agentMsgRouter: AgentMsgRouter): Props =
    Props(new ActorStateCleanupExecutor(appConfig, agentMsgRouter))
}

case object ProcessPending extends ActorMessageObject

object BatchProcessingStatus {
  def empty: BatchProcessingStatus = BatchProcessingStatus(Set.empty, 0)
}
case class BatchProcessingStatus(candidateDIDs: Set[DID], processed: Int) {
  def isInProgress: Boolean = candidateDIDs.size > processed
  def isCompleted: Boolean = candidateDIDs.size == processed
  def isEmpty: Boolean = candidateDIDs.isEmpty
}

case class CurrentStatus(statusOpt: Option[ProcessingStatus], batchStatus: BatchProcessingStatus) extends ActorMessageClass