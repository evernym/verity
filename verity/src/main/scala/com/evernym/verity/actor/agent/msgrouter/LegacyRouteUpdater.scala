package com.evernym.verity.actor.agent.msgrouter

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import akka.persistence.DeleteMessagesSuccess
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.UpdateRoute
import com.evernym.verity.actor.cluster_singleton.ForLegacyRouteFixManager
import com.evernym.verity.actor.cluster_singleton.fixlegacyroutes.Completed
import com.evernym.verity.actor.persistence.{BasePersistentActor, Done}
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, ForIdentifier, RouteSet}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.util.Util.getActorRefFromSelection
import com.typesafe.scalalogging.Logger


/**
 * updates legacy agent routes of one 'agent route store' actor
 * @param appConfig
 * @param agentMsgRouter
 */
class LegacyRouteUpdater(val appConfig: AppConfig, val agentMsgRouter: AgentMsgRouter)
  extends BasePersistentActor {

  override def receiveCmd: Receive = {
    case GetUpdaterStatus                       => sender ! CurrentStatus(statusOpt, batchStatus)
    case r: ProcessRouteStore                   => handleProcessRouteStore(r)
    case rp: RouteProcessed                     => handleRouteProcessed(rp)
    case rd: CandidateRoutesToBeProcessed       => processCandidateRoutes(rd.dids)

    case ProcessPending if statusOpt.isDefined  => processBatch()
    case ProcessPending if statusOpt.isEmpty    => //nothing to do

    //below command is received from LegacyRouteFixManager as a response of 'Completed' sent by this actor
    case Done                                   =>
    //below command is received from LegacyRouteFixManager once it has successfully recorded 'Completed'
    case Destroy                                => handleDestroy()
  }

  override def receiveEvent: Receive = {
    case su: StatusUpdated =>
      statusOpt = Option(ProcessingStatus(su.agentRouteStoreEntityId, su.totalRoutes, su.processedRoutes))
    case _: RouteProcessed =>
      statusOpt = statusOpt.map(s => s.copy(totalProcessed = s.totalProcessed + 1))
  }

  def handleRouteProcessed(rp: RouteProcessed): Unit = {
    logger.debug(s"LRF [$persistenceId] [LRU->LRU]: received RouteProcessed: " + rp)
    writeAndApply(rp)
    batchStatus = batchStatus.copy(processed = batchStatus.processed + 1)
    if (batchStatus.candidates == batchStatus.processed) {
      batchStatus = BatchStatus.empty
    }
  }

  def handleProcessRouteStore(prs: ProcessRouteStore): Unit = {
    logger.debug(s"LRF [$persistenceId] [LRM->LRU] received ProcessRouteStore: " + prs)
    if (statusOpt.isDefined) {
      logger.debug(s"LRF [$persistenceId] status: " + status)
      sender ! status
    } else {
      val event = StatusUpdated(prs.agentRouteStoreEntityId, prs.totalRoutes)
      logger.debug(s"LRF [$persistenceId] recording event: " + event)
      writeApplyAndSendItBack(event)
    }
  }

  def processBatch(): Unit = {
    logger.debug(s"LRF [$persistenceId] [LRU->LRU] process batch started")
    if (status.isAllCompleted) {    // all routes are processed
      logger.debug(s"LRF [$persistenceId] route fixing completed for agent route store entity id: " + status.agentRouteStoreEntityId)
      singletonParentProxyActor ! ForLegacyRouteFixManager(Completed(entityId, status.totalProcessed))
    } else if (! batchStatus.inProgress) {    //no batch in progress
      logger.debug(s"LRF [$persistenceId] no batch is in progress: " + batchStatus)
      val cmd = GetPotentialLegacyRouteBatch(status.totalCandidates, status.totalProcessed, batchSize)
      agentRouteStoreRegion ! ForIdentifier(status.agentRouteStoreEntityId, cmd)
    }
  }

  def handleDestroy(): Unit = {
    //once this actor is done with processing all 'routes' for given 'agent route store' actor
    //then it is no longer needed and should cleanup its persistence
    logger.debug(s"LRF [$persistenceId] [LRM->LRU] route fixing completed for legacy updater '$entityId', and this actor will be destroyed")
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
    logger.debug(s"LRF [$persistenceId] [ARS->LRU] received candidates to be processed: " + candidateDIDs.size)
    candidateDIDs.foreach { did =>
      logger.debug(s"LRF [$persistenceId] did: " + did)
      agentMsgRouter.execute(InternalMsgRouteParam(did, UpdateRoute)).map{
        case _ @ ( _:RouteAlreadySet | _: RouteSet) =>
          logger.debug(s"LRF [$persistenceId] did processed: " + did)
          self ! RouteProcessed(did)
        case x => logger.error(s"LRF [$persistenceId] [AA->LRU] received unexpected response from agent route store: " + x)
      }.recover {
        case e: RuntimeException =>
          logger.error("unhandled error while fixing legacy agent routes: " + e.getMessage)
      }
    }
    if (candidateDIDs.nonEmpty) {
      batchStatus = BatchStatus(inProgress = true, candidateDIDs.size, 0)
    } else {
      if (status.totalCandidates > 0) {
        logger.warn(s"LRF [$persistenceId] suspicious, totalCandidates: ${status.totalCandidates}, but received 0")
      } else {
        logger.debug(s"LRF [$persistenceId] received no candidates to be processed: " + status)
        writeAndApply(StatusUpdated(status.agentRouteStoreEntityId, status.totalCandidates, status.totalCandidates))
      }
    }
  }

  var statusOpt: Option[ProcessingStatus] = None
  def status: ProcessingStatus = statusOpt.getOrElse(throw new RuntimeException(s"LRF [$persistenceId] status not yet initialized"))
  var batchStatus: BatchStatus = BatchStatus.empty

  override def persistenceEncryptionKey: String = this.getClass.getSimpleName

  lazy val batchSize: Int =
    appConfig.getConfigIntOption(CommonConfig.ARS_FIX_LEGACY_ROUTES_UPDATER_BATCH_SIZE)
    .getOrElse(5)

  val isFixLegacyRoutesEnabled: Boolean =
    appConfig
      .getConfigBooleanOption(CommonConfig.ARS_FIX_LEGACY_ROUTES_ENABLED)
      .getOrElse(false)

  val agentRouteStoreRegion: ActorRef = ClusterSharding.get(context.system).shardRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME)

  lazy val singletonParentProxyActor: ActorRef =
    getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)

  lazy val scheduledJobInitialDelay: Int =
    appConfig
      .getConfigIntOption(ARS_FIX_LEGACY_ROUTES_UPDATER_SCHEDULED_JOB_INITIAL_DELAY_IN_SECONDS)
      .getOrElse(60)

  lazy val scheduledJobInterval: Int =
    appConfig
      .getConfigIntOption(ARS_FIX_LEGACY_ROUTES_UPDATER_SCHEDULED_JOB_INTERVAL_IN_SECONDS)
      .getOrElse(300)

  scheduleJob("periodic_job", scheduledJobInitialDelay, scheduledJobInterval, ProcessPending)

  private val logger: Logger = LoggingUtil.getLoggerByClass(classOf[LegacyRouteUpdater])
}

//status
case class ProcessingStatus(agentRouteStoreEntityId: EntityId, totalCandidates: Int, totalProcessed: Int) extends ActorMessageClass {
  def isAllCompleted: Boolean = totalCandidates == totalProcessed
}

//incoming message
case object GetUpdaterStatus extends ActorMessageObject
case class ProcessRouteStore(agentRouteStoreEntityId: String, totalRoutes: Int, processedRoutes: Int = 0) extends ActorMessageClass
case object Destroy extends ActorMessageObject

object LegacyRouteUpdater {
  def props(appConfig: AppConfig, agentMsgRouter: AgentMsgRouter): Props =
    Props(new LegacyRouteUpdater(appConfig, agentMsgRouter))
}

case object ProcessPending extends ActorMessageObject

object BatchStatus {
  def empty: BatchStatus = BatchStatus(inProgress = false, 0, 0)
}
case class BatchStatus(inProgress: Boolean, candidates: Int, processed: Int)

case class CurrentStatus(statusOpt: Option[ProcessingStatus], batchStatus: BatchStatus) extends ActorMessageClass