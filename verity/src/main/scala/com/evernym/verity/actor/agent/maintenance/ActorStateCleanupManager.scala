package com.evernym.verity.actor.agent.maintenance

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msgrouter._
import com.evernym.verity.actor.persistence.{Done, SingletonChildrenPersistentActor, Stop}
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, Completed, ExecutorDeleted, ForIdentifier, Registered, StatusUpdated}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.Future

/**
 * route store manager, orchestrates each route store processing
 * @param appConfig application configuration object
 */
class ActorStateCleanupManager(val appConfig: AppConfig)
  extends SingletonChildrenPersistentActor
    with ActorStateCleanupBase {

  override def receiveCmd: Receive = {
    case gs: GetManagerStatus       => handleGetStatus(gs)
    case r: RegisteredRouteSummary  => handleRegister(r)
    case c: Completed               => handleCompleted(c)
    case ProcessPending             => processPending()
    case Reset                      => handleReset()

    //receives from ActorStateCleanupExecutor as part of response of 'ProcessRouteStore' command
    case _: StatusUpdated           => //nothing to do
    case d: Destroyed               => handleDestroyed(d)

    case rss: RouteStoreStatus      =>
      if (! completed.contains(rss.agentRouteStoreEntityId)) {
        inProgress += rss.agentRouteStoreEntityId -> rss.totalProcessed
        if (rss.inProgressCleanupStatus.isEmpty) {
          cleanupStatus -= rss.agentRouteStoreEntityId
        } else {
          cleanupStatus += rss.agentRouteStoreEntityId -> rss.inProgressCleanupStatus
        }
      }
  }

  override def receiveEvent: Receive = {
    case r: Registered =>
      registered += r.entityId -> r.totalCandidateRoutes

    case c: Completed  =>
      inProgress -= c.entityId
      cleanupStatus -= c.entityId
      completed += c.entityId -> c.totalProcessedRoutes

    case ed: ExecutorDeleted =>
      executorDestroyed += ed.entityId
  }

  def handleGetStatus(gs: GetManagerStatus): Unit = {
    val s = ManagerStatus(
      registered.size,
      registered.values.sum,
      completed.size,
      completed.values.sum + inProgress.values.sum,
      cleanupStatus)
    if (gs.includeDetails) {
      sender ! s.copy(registeredRouteStores = Some(registered), resetStatus = Option(resetStatus))
    } else {
      sender ! s
    }
  }

  def handleReset(): Unit = {
    if (! resetStatus.isInProgress) {
      val candidates = registered.filter(_._2 > 0).keySet
      if (candidates.nonEmpty) {
        resetStatus = ResetStatus(isInProgress = true, candidates.map(_ -> false).toMap)
        sendDestroyExecutor()
      } else {
        completeResetProcess()
      }
    } else if (resetStatus.isAllExecutorDestroyed) {
      completeResetProcess()
    }
    sender ! Done
  }

  def sendDestroyExecutor(): Unit = {
    resetStatus.executorStatus.find(_._2 == false).foreach { case (entityId, _) =>
      sendMsgToActorStateCleanupExecutor(entityId, Destroy)
    }
  }

  def handleDestroyed(d: Destroyed): Unit = {
    writeAndApply(ExecutorDeleted(d.entityId))
    if (resetStatus.isInProgress) {
      resetStatus = resetStatus.copy(executorStatus = resetStatus.executorStatus ++ Map(d.entityId -> true))
      if (resetStatus.isAllExecutorDestroyed) {
        completeResetProcess()
      } else {
        sendDestroyExecutor()
      }
    }
  }

  def completeResetProcess(): Unit = {
    deleteEventsInBatches()
  }

  def postAllEventDeleted(): Unit = {
    resetStatus = ResetStatus.empty
    registered = Map.empty
    completed = Map.empty
    inProgress = Map.empty
    cleanupStatus = Map.empty
    lastRequestedBucketId = -1
    toSeqNoDeleted = 0
    stopActor()
  }

  def pendingBatchedRouteStores: Map[EntityId, RoutesCount] =
    registered
      .filter(r => ! completed.contains(r._1))
      .toSeq.sortBy(_._1).toMap   //to make this pending list deterministic
      .take(processorBatchSize)

  def processPending(): Unit = {
    processRoutes()
    deleteCompletedExecutors()
  }

  def deleteCompletedExecutors(): Unit = {
//    (completed.keySet -- executorDestroyed).foreach { eid =>
//      sendMsgToActorStateCleanupExecutor(eid, Destroy)
//    }
  }

  def processRoutes(): Unit = {
    if (isActorStateCleanupEnabled) {
      if (registered.size != totalBuckets) {
        logger.debug(s"ASC [$persistenceId] all agent route store entity ids are not yet registered...")
        //first complete registration of all agent route store actors (totalBuckets)
        sendAnyPendingRegistrationRequest()
      } else if (completed.size != registered.size) {
        logger.debug(s"ASC [$persistenceId] all agent route store entity ids are registered but not yet completed")
        //once all expected agent store actors registration is completed
        //then start processing as per configured batch size
        val candidateRouteStores = pendingBatchedRouteStores
        Future {
          candidateRouteStores.foreach { case (agentRouteStoreEntityId, totalRoutes) =>
            sendMsgToActorStateCleanupExecutor(agentRouteStoreEntityId, ProcessRouteStore(agentRouteStoreEntityId, totalRoutes))
            Thread.sleep(processorBatchItemSleepIntervalInMillis) //this is to make sure it doesn't hit the database too hard and impact the running system.
          }
        }
      } else {
        stopAllScheduledJobs()
      }
    }
  }

  def sendMsgToActorStateCleanupExecutor(agentRouteStoreEntityId: EntityId, msg: Any): Unit = {
    actorStateCleanupExecutorRegion ! ForIdentifier(agentRouteStoreEntityId, msg)
  }

  /**
   * agent msg router actor registering with this manager so that
   * its route can be fixed
   * @param r register
   */
  def handleRegister(r: RegisteredRouteSummary): Unit = {
    if (completed.contains(r.entityId)) {
      sender ! AlreadyCompleted
    } else if (registered.contains(r.entityId)) {
      sender ! AlreadyRegistered
    } else {
      writeApplyAndSendItBack(Registered(r.entityId, r.totalCandidateRoutes))
      if (r.totalCandidateRoutes <= 0 ) {
        handleCompleted(Completed(r.entityId, r.totalCandidateRoutes), duringRegistration = true)
      }
    }
    sender ! Stop()   //stop route store actor
  }

  /**
   * indicates that an actor state cleanup completed
   * for all actors belonging to the given route store entity id
   * @param c completed
   */
  def handleCompleted(c: Completed, duringRegistration: Boolean = false): Unit = {
    if (! completed.contains(c.entityId)) {
      writeAndApply(c)
    } else if (duringRegistration) {
      sender ! AlreadyCompleted
    }
    if (c.totalProcessedRoutes > 0) {
      //uncomment below line if you want to delete all events of the
      // 'ActorStateCleanupExecutor' actor with entity id 'c.entityId'
      //sendMsgToActorStateCleanupExecutor(c.entityId, Destroy)
    }
  }

  def sendAnyPendingRegistrationRequest(): Unit = {
    var candidateEntityIds = Set.empty[String]
    while ( (candidateEntityIds.size < registrationBatchSize) && (lastRequestedBucketId < totalBuckets-1)) {
      val nextBucketId = lastRequestedBucketId + 1
      lastRequestedBucketId = nextBucketId
      val entityId = RoutingAgentBucketMapperV1.entityIdByBucketId(nextBucketId)
      if (! registered.contains(entityId)) {
        candidateEntityIds += entityId
      }
    }
    Future {
      candidateEntityIds.foreach { entityId =>
        agentRouteStoreRegion ! ForIdentifier(entityId, GetRegisteredRouteSummary)
        Thread.sleep(registrationBatchItemSleepIntervalInMillis) //this is to make sure it doesn't hit the database too hard and impact the running system.
      }
    }
  }

  lazy val actorStateCleanupExecutorRegion: ActorRef =
    ClusterSharding.get(context.system).shardRegion(ACTOR_STATE_CLEANUP_EXECUTOR)

  type PersistenceId = String
  type RoutesCount = Int

  var executorDestroyed: Set[EntityId] = Set.empty
  var completed: Map[EntityId, RoutesCount] = Map.empty
  var inProgress: Map[EntityId, RoutesCount] = Map.empty
  var cleanupStatus: Map[EntityId, Map[DID, CleanupStatus]] = Map.empty
  var registered: Map[EntityId, RoutesCount] = Map.empty
  var resetStatus: ResetStatus = ResetStatus.empty

  //currently, based on the sharding strategy, there can be only max 100 sharded actors
  // might have created with below versioning scheme
  var totalBuckets = 100

  var lastRequestedBucketId = -1

  lazy val agentRouteStoreRegion: ActorRef =
    ClusterSharding.get(context.system).shardRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME)

  lazy val scheduledJobInitialDelay: Int =
    appConfig
      .getConfigIntOption(AAS_CLEANUP_MANAGER_SCHEDULED_JOB_INITIAL_DELAY_IN_SECONDS)
      .getOrElse(60)

  lazy val scheduledJobInterval: Int =
    appConfig
      .getConfigIntOption(AAS_CLEANUP_MANAGER_SCHEDULED_JOB_INTERVAL_IN_SECONDS)
      .getOrElse(300)

  lazy val registrationBatchSize: Int =
    appConfig.getConfigIntOption(CommonConfig.AAS_CLEANUP_MANAGER_REGISTRATION_BATCH_SIZE)
      .getOrElse(1)
  lazy val registrationBatchItemSleepIntervalInMillis: Int =
    appConfig.getConfigIntOption(CommonConfig.AAS_CLEANUP_MANAGER_REGISTRATION_BATCH_ITEM_SLEEP_INTERVAL_IN_MILLIS)
      .getOrElse(5)

  lazy val processorBatchSize: Int =
    appConfig.getConfigIntOption(CommonConfig.AAS_CLEANUP_MANAGER_PROCESSOR_BATCH_SIZE)
      .getOrElse(5)
  lazy val processorBatchItemSleepIntervalInMillis: Int =
    appConfig.getConfigIntOption(CommonConfig.AAS_CLEANUP_MANAGER_PROCESSOR_BATCH_ITEM_SLEEP_INTERVAL_IN_MILLIS)
      .getOrElse(5)

  scheduleJob("periodic_job", scheduledJobInitialDelay, scheduledJobInterval, ProcessPending)

}

/**
 *
 * @param registeredRouteStoreActorCount total 'agent route store' actor who registered with this actor
 * @param totalCandidateAgentActors total candidate agent-actors (belonging to all registered routing actors)
 * @param processedRouteStoreActorCount total 'agent route store' actor processed (out of registeredRouteStoreActorCount)
 * @param totalProcessedAgentActors total processed agent-actors (out of totalCandidateAgentActors)
 */
case class ManagerStatus(registeredRouteStoreActorCount: Int,
                         totalCandidateAgentActors: Int,
                         processedRouteStoreActorCount: Int,
                         totalProcessedAgentActors: Int,
                         inProgressCleanupStatus: Map[EntityId, Map[DID, CleanupStatus]],
                         resetStatus: Option[ResetStatus] = None,
                         registeredRouteStores: Option[Map[EntityId, Int]] = None) extends ActorMessageClass

object ResetStatus {
  def empty: ResetStatus = ResetStatus(isInProgress = false, Map.empty)
}
case class ResetStatus(isInProgress: Boolean, executorStatus: Map[EntityId, Boolean]) {
  def isAllExecutorDestroyed: Boolean = executorStatus.forall(_._2 == true)
}

//incoming messages
case class RegisteredRouteSummary(entityId: EntityId, totalCandidateRoutes: Int) extends ActorMessageClass
case class GetManagerStatus(includeDetails: Boolean = false) extends ActorMessageClass
case object Reset extends ActorMessageObject

//outgoing messages
case object AlreadyCompleted extends ActorMessageObject
case object AlreadyRegistered extends ActorMessageObject

object ActorStateCleanupManager {
  val name: String = ACTOR_STATE_CLEANUP_MANAGER
  def props(appConfig: AppConfig): Props = Props(new ActorStateCleanupManager(appConfig))
}
