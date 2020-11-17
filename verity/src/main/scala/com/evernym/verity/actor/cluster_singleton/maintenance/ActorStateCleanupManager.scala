package com.evernym.verity.actor.cluster_singleton.maintenance

import java.util.UUID

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import akka.persistence.DeleteMessagesSuccess
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.maintenance.{Destroy, ProcessRouteStore, ProcessingStatus}
import com.evernym.verity.actor.agent.msgrouter._
import com.evernym.verity.actor.persistence.{Done, SingletonChildrenPersistentActor}
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, ForIdentifier}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.logging.LoggingUtil
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * route store manager, orchestrates each route store processing
 * @param appConfig application configuration object
 */
class ActorStateCleanupManager(val appConfig: AppConfig)
  extends SingletonChildrenPersistentActor {

  override def receiveCmd: Receive = {
    case GetStatus                => sender ! Status(registered.size, completed.size, registered.values.sum, completed.values.sum)
    case r: Register              => handleRegister(r)
    case c: Completed             => handleCompleted(c)
    case ProcessPending           => processPending()

      //receives from LegacyRouteFixer as part of response of 'ProcessRouteStore' command
    case _ @ (_:ProcessingStatus | _: StatusUpdated)     => //nothing to do
    case Reset                    => handleReset()
  }

  override def receiveEvent: Receive = {
    case r: Registered =>
      registered += r.entityId -> r.totalCandidateRoutes

    case c: Completed  =>
      completed += c.entityId -> c.totalProcessedRoutes
  }

  def handleReset(): Unit = {
    registered.keySet.foreach { entityId =>
      sendMsgToActorStateCleanupExecutor(entityId, Destroy)
      Thread.sleep(40)
    }
    deleteMessages(lastSequenceNr)
    sender ! Done
  }

  override def handleDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit = {
    stopActor()
    super.handleDeleteMsgSuccess(dms)
  }

  def pendingBatchedRouteStores: Map[EntityId, RoutesCount] =
    registered
      .filter(r => ! completed.contains(r._1))
      .toSeq.sortBy(_._1).toMap   //to make this pending list deterministic
      .take(processorBatchSize)

  def processPending(): Unit = {
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
      }
    }
  }

  def sendMsgToActorStateCleanupExecutor(agentRouteStoreEntityId: EntityId, msg: Any): Unit = {
    actorStateCleanupExecutorRegion ! ForIdentifier(agentRouteStoreEntityId, msg)
  }

  def actorStateCleanupExecutorRegion: ActorRef = ClusterSharding.get(context.system).shardRegion(ACTOR_STATE_CLEANUP_EXECUTOR)

  /**
   * agent msg router actor registering with this manager so that
   * its route can be fixed
   * @param r register
   */
  def handleRegister(r: Register): Unit = {
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
  }

  /**
   * indicates that an actor state cleanup completed
   * for all actors belonging to the given route store entity id
   * @param c completed
   */
  def handleCompleted(c: Completed, duringRegistration: Boolean = false): Unit = {
    if (! completed.contains(c.entityId)) {
      writeAndApply(c)
    } else if (! duringRegistration) {
      sender ! AlreadyCompleted
    }
    if (c.totalProcessedRoutes > 0)
      sendMsgToActorStateCleanupExecutor(c.entityId, Destroy)
  }

  def sendAnyPendingRegistrationRequest(): Unit = {
    var requestsSent = 0
    while ( (requestsSent < registrationBatchSize) && (lastRequestedBucketId < totalBuckets-1)) {
      val nextBucketId = lastRequestedBucketId + 1
      lastRequestedBucketId = nextBucketId
      val entityId = "v1" + "-" + UUID.nameUUIDFromBytes(nextBucketId.toString.getBytes).toString
      if (! registered.contains(entityId)) {
        agentRouteStoreRegion ! ForIdentifier(entityId, SendAllRouteRegistrationRequest)
        requestsSent += 1
        Thread.sleep(registrationBatchItemSleepIntervalInMillis)   //this is to make sure it doesn't hit the database too hard and impact the running system.
      }
    }
  }

  //this is internal actor for short period of time and doesn't contain any sensitive data
  override def persistenceEncryptionKey: String = this.getClass.getSimpleName

  type PersistenceId = String
  type RoutesCount = Int

  var completed: Map[EntityId, RoutesCount] = Map.empty
  var registered: Map[EntityId, RoutesCount] = Map.empty

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

  def isActorStateCleanupEnabled: Boolean =
    appConfig
      .getConfigBooleanOption(CommonConfig.AGENT_ACTOR_STATE_CLEANUP_ENABLED)
      .getOrElse(false)

  private val logger: Logger = LoggingUtil.getLoggerByClass(classOf[ActorStateCleanupManager])
}

/**
 *
 * @param registeredRouteStoreActorCount total 'agent route store' actor who registered with this actor
 * @param processedRouteStoreActorCount total 'agent route store' actor processed (out of registeredRouteStoreActorCount)
 * @param totalCandidateAgentActors total candidate agent-actors (belonging to all registered routing actors)
 * @param totalProcessedAgentActors total processed agent-actors (out of totalCandidateAgentActors)
 */
case class Status(registeredRouteStoreActorCount: Int,
                  processedRouteStoreActorCount: Int,
                  totalCandidateAgentActors: Int,
                  totalProcessedAgentActors: Int) extends ActorMessageClass

//incoming messages
case class Register(entityId: EntityId, totalCandidateRoutes: Int) extends ActorMessageClass
case object ProcessPending extends ActorMessageObject
case object GetStatus extends ActorMessageObject
case object Reset extends ActorMessageObject

//outgoing messages
case object AlreadyCompleted extends ActorMessageObject
case object AlreadyRegistered extends ActorMessageObject

object ActorStateCleanupManager {
  val name: String = ACTOR_STATE_CLEANUP_MANAGER
  def props(appConfig: AppConfig): Props = Props(new ActorStateCleanupManager(appConfig))
}