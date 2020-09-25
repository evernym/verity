package com.evernym.verity.actor.cluster_singleton.legacyroutefixmanager

import java.util.UUID

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import akka.persistence.DeleteMessagesSuccess
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msgrouter._
import com.evernym.verity.actor.cluster_singleton.fixlegacyroutes.{Completed, Registered}
import com.evernym.verity.actor.persistence.{Done, SingletonChildrenPersistentActor}
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, ForIdentifier}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.logging.LoggingUtil
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * orchestrates and maintains status of legacy route fixing process
 * @param appConfig
 */
class LegacyRouteFixManager(val appConfig: AppConfig) extends SingletonChildrenPersistentActor {

  override def receiveCmd: Receive = {
    case GetStatus                => sender ! Status(registered.size, completed.size, registered.values.sum, completed.values.sum)
    case r: Register              => handleRegister(r)
    case c: Completed             => handleCompleted(c)
    case ProcessPending           => processPending()

      //receives from LegacyRouteFixer as part of response of 'ProcessFixingLegacyRoutes' command
    case _ @ (_:ProcessingStatus | _: StatusUpdated)     => //nothing to do
    case Reset                    => handleReset()
  }

  override def receiveEvent: Receive = {
    case r: Registered =>
      registered += r.entityId -> r.totalCandidateRoutes

    case c: Completed  =>
      completed += c.entityId -> c.totalProcessedRoutes
      if (completionInProgress.contains(c.entityId)) {
        sendMsgToLegacyRouteFixer(c.entityId, Destroy)
        completionInProgress -= c.entityId
      }
  }

  def handleReset(): Unit = {
    registered.keySet.foreach { entityId =>
      sendMsgToLegacyRouteFixer(entityId, Destroy)
      Thread.sleep(40)
    }
    deleteMessages(lastSequenceNr)
    sender ! Done
  }

  override def handleDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit = {
    stopActor()
    super.handleDeleteMsgSuccess(dms)
  }

  def pendingRouteStores: Map[EntityId, RoutesCount] =
    registered.filter(r => ! completed.contains(r._1)).take(updaterBatchSize)

  def processPending(): Unit = {
    if (isFixLegacyRoutesEnabled) {
      if (registered.size != totalBuckets) {
        logger.debug(s"LRF [$persistenceId] all agent route store entity ids are not yet registered...")
        //first complete registration of all agent route store actors (totalBuckets)
        sendAnyPendingRegistrationRequest()
      } else {
        logger.debug(s"LRF [$persistenceId] all agent route store entity ids are registered.")
        //once all expected agent store actors registration is completed
        //then start processing as per configured batch size
        val routeStores = pendingRouteStores
        Future {
          routeStores.foreach { case (agentRouteStoreEntityId, totalRoutes) =>
            sendMsgToLegacyRouteFixer(agentRouteStoreEntityId, ProcessRouteStore(agentRouteStoreEntityId, totalRoutes))
            Thread.sleep(updaterBatchItemSleepIntervalInMillis) //this is to make sure it doesn't hit the database too hard and impact the running system.
          }
        }
      }
    }
  }

  def sendMsgToLegacyRouteFixer(agentRouteStoreEntityId: EntityId, msg: Any): Unit = {
    legacyRouteFixerRegion ! ForIdentifier(agentRouteStoreEntityId, msg)
  }

  def legacyRouteFixerRegion: ActorRef = ClusterSharding.get(context.system).shardRegion(LEGACY_ROUTE_UPDATER)

  /**
   * agent msg router actor registering with this manager so that
   * its route can be fixed
   * @param r
   */
  def handleRegister(r: Register): Unit = {
    if (completed.contains(r.entityId)) {
      sender ! AlreadyCompleted
    } else if (registered.contains(r.entityId)) {
      sender ! AlreadyRegistered
    } else {
      writeApplyAndSendItBack(Registered(r.entityId, r.totalCandidateRoutes))
      if (r.totalCandidateRoutes <= 0 ) {
        handleCompleted(Completed(r.entityId, r.totalCandidateRoutes))
      }
    }
  }

  /**
   * indicates that an agent route store actor's routing fix process is completed
   * @param c
   */
  def handleCompleted(c: Completed): Unit = {
    if (completed.contains(c.entityId)) {
      sender ! AlreadyCompleted
    } else {
      completionInProgress += c.entityId
      writeAndApply(c)
    }
  }

  def sendAnyPendingRegistrationRequest(): Unit = {
    Future {
      (1 to registrationBatchSize).foreach { _ =>
        val nextBucketId = lastRequestedBucketId + 1
        lastRequestedBucketId = nextBucketId
        val entityId = "v1" + "-" + UUID.nameUUIDFromBytes(nextBucketId.toString.getBytes).toString
        if (registered.contains(entityId)) {
          sendAnyPendingRegistrationRequest()
        } else {
          agentRouteStoreRegion ! ForIdentifier(entityId, GetPotentialLegacyRouteSummary)
          Thread.sleep(registrationBatchItemSleepIntervalInMillis)   //this is to make sure it doesn't hit the database too hard and impact the running system.
        }
      }
    }
  }

  //this is internal actor for short period of time and doesn't contain any sensitive data
  override def persistenceEncryptionKey: String = this.getClass.getSimpleName

  type PersistenceId = String
  type RoutesCount = Int

  var completed: Map[EntityId, RoutesCount] = Map.empty
  var registered: Map[EntityId, RoutesCount] = Map.empty
  var completionInProgress: Set[EntityId] = Set.empty

  //currently, based on the sharding strategy, there can be only max 100 sharded actors
  // might have created with below versioning scheme
  var totalBuckets = 100

  var lastRequestedBucketId = 0

  lazy val agentRouteStoreRegion: ActorRef =
    ClusterSharding.get(context.system).shardRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME)

  lazy val scheduledJobInitialDelay: Int =
    appConfig
      .getConfigIntOption(ARS_FIX_LEGACY_ROUTES_MANAGER_SCHEDULED_JOB_INITIAL_DELAY_IN_SECONDS)
      .getOrElse(60)

  lazy val scheduledJobInterval: Int =
    appConfig
      .getConfigIntOption(ARS_FIX_LEGACY_ROUTES_MANAGER_SCHEDULED_JOB_INTERVAL_IN_SECONDS)
      .getOrElse(300)

  lazy val registrationBatchSize: Int =
    appConfig.getConfigIntOption(CommonConfig.ARS_FIX_LEGACY_ROUTES_MANAGER_REGISTRATION_BATCH_SIZE)
      .getOrElse(1)
  lazy val registrationBatchItemSleepIntervalInMillis: Int =
    appConfig.getConfigIntOption(CommonConfig.ARS_FIX_LEGACY_ROUTES_MANAGER_REGISTRATION_BATCH_ITEM_SLEEP_INTERVAL_IN_MILLIS)
      .getOrElse(5)

  lazy val updaterBatchSize: Int =
    appConfig.getConfigIntOption(CommonConfig.ARS_FIX_LEGACY_ROUTES_MANAGER_UPDATER_BATCH_SIZE)
      .getOrElse(5)
  lazy val updaterBatchItemSleepIntervalInMillis: Int =
    appConfig.getConfigIntOption(CommonConfig.ARS_FIX_LEGACY_ROUTES_MANAGER_UPDATER_BATCH_ITEM_SLEEP_INTERVAL_IN_MILLIS)
      .getOrElse(5)

  scheduleJob("periodic_job", scheduledJobInitialDelay, scheduledJobInterval, ProcessPending)

  def isFixLegacyRoutesEnabled: Boolean =
    appConfig
      .getConfigBooleanOption(CommonConfig.ARS_FIX_LEGACY_ROUTES_ENABLED)
      .getOrElse(false)

  private val logger: Logger = LoggingUtil.getLoggerByClass(classOf[LegacyRouteFixManager])
}

/**
 *
 * @param registeredActorCount total 'agent route store' actor who registered with this actor
 * @param processedActorCount total 'agent route store' actor (out of registeredActorCount) processed (routes fixed)
 * @param totalCandidateRoutes total candidate routes (belonging to all registered actors)
 * @param totalProcessedRoutes total processed routes (out of totalCandidateRoute)
 */
case class Status(registeredActorCount: Int, processedActorCount: Int,
                  totalCandidateRoutes: Int, totalProcessedRoutes: Int) extends ActorMessageClass

//incoming messages
case class Register(entityId: EntityId, totalCandidateRoutes: Int) extends ActorMessageClass
case object ProcessPending extends ActorMessageObject
case object GetStatus extends ActorMessageObject
case object Reset extends ActorMessageObject

//outgoing messages
case object AlreadyCompleted extends ActorMessageObject
case object AlreadyRegistered extends ActorMessageObject

object LegacyRouteFixManager {
  val name: String = LEGACY_ROUTE_FIX_MANAGER
  def props(appConfig: AppConfig): Props = Props(new LegacyRouteFixManager(appConfig))
}