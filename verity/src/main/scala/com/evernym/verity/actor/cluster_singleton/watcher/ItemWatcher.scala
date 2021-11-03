package com.evernym.verity.actor.cluster_singleton.watcher

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import com.evernym.verity.actor.cluster_singleton.ForWatcherManagerChild
import com.evernym.verity.actor.itemmanager.ItemCommonConstants._
import com.evernym.verity.actor.itemmanager.ItemCommonType.ItemId
import com.evernym.verity.actor.itemmanager._
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, HasAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.actor.agent.EntityTypeMapper
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.actor.itemmanager.ItemConfigManager.versionedItemManagerEntityId
import com.evernym.verity.util2.ActorErrorResp
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext
import scala.util.Try


/**
 * this is parent for any watcher manager actor we create
 * (as of today there is only one such watcher manager called 'uap-actor-watcher' [uap = user agent pairwise])
 * @param appConfig application configuration
 */
class WatcherManager(val appConfig: AppConfig, ec: ExecutionContext)
  extends CoreActorExtended {
  val logger: Logger = getLoggerByClass(classOf[WatcherManager])

  //NOTE: don't make below statement lazy, it needs to start as soon as possible
  val actorWatcher: ActorRef = context.actorOf(ActorWatcher.props(appConfig, ec), "ActorWatcher")

  override def receiveCmd: Receive = {
    case fwmc: ForWatcherManagerChild => actorWatcher forward fwmc.cmd
  }
}

object WatcherManager {
  val name: String = WATCHER_MANAGER
  def props(appConfig: AppConfig, ec: ExecutionContext): Props = Props(new WatcherManager(appConfig, ec))
}

class ActorWatcher(val appConfig: AppConfig, ec: ExecutionContext)
  extends CoreActorExtended
    with HasActorResponseTimeout
    with HasAppConfig {

  import ActorWatcher._
  implicit val executionContext = ec

  val logger: Logger = getLoggerByClass(classOf[ActorWatcher])

  override def receiveCmd: Receive = {
    case ai: AddItem                    => addItem(ai)
    case ri: RemoveItem                 => removeItem(ri)
    case fai: FetchedActiveItems        => updateFetchedItems(fai)
    case CheckForPeriodicTaskExecution  => handlePeriodicTaskExecution()
    case ItemManagerConfigAlreadySet    => //this will be received from item manager if it is already configured
    case _: ItemManagerStateDetail      => //this will be received from item manager if it got configured for first time
    case _: ItemCmdResponse             => //nothing to do
    case ar: ActorErrorResp             => logger.error("received unexpected message: " + ar)
  }

  def handlePeriodicTaskExecution(): Unit = {
    //fetch active items only when it has processed previously fetched active items
    if (activeItems.isEmpty) {
      fetchAllActiveItems()
    }
    processOneBatchOfFetchedActiveItems()
  }

  /**
   * configuration which decides if items should be migrated to next linked container or not.
   * @return
   */
  lazy val migrateItemsToNextLinkedContainer: Boolean = true

  lazy val itemManagerEntityId =
    versionedItemManagerEntityId(itemManagerEntityIdPrefix, appConfig)

  def buildItemManagerConfig: SetItemManagerConfig = SetItemManagerConfig(
    itemManagerEntityId,
    migrateItemsToNextLinkedContainer)

  lazy val itemManagerRegion: ActorRef = ClusterSharding(context.system).shardRegion(ITEM_MANAGER_REGION_ACTOR_NAME)

  def setItemManagerConfig(): Unit = {
    itemManagerRegion ! ForIdentifier(itemManagerEntityId, ExternalCmdWrapper(buildItemManagerConfig, None))
  }

  private def addItem(ai: AddItem): Unit = {
    val itemId = buildUniqueItemId(ai.itemId, ai.itemEntityType)
    val uip = UpdateItem(itemId, Option(ITEM_STATUS_ACTIVE), ai.detail, None)
    itemManagerRegion ! ForIdentifier(itemManagerEntityId, ExternalCmdWrapper(uip, None))
  }

  private def removeItem(ri: RemoveItem): Unit = {
    val itemId = buildUniqueItemId(ri.itemId, ri.itemEntityType)
    val uip = UpdateItem(itemId, Option(ITEM_STATUS_REMOVED), None, None)
    itemManagerRegion ! ForIdentifier(itemManagerEntityId, ExternalCmdWrapper(uip, None))
  }

  private def sendMsgToWatchedItem(itemId: String): Unit = {
    val (entityId, regionActor) = {
      val tokens = itemId.split("#", 2)
      (tokens.head, entityRegion(tokens.last))
    }
    regionActor ! ForIdentifier(entityId, CheckWatchedItem)
  }

  private def buildUniqueItemId(entityId: String, entityType: String): String = {
    if (entityId.contains("#"))
      throw new RuntimeException("invalid entity id (it shouldn't contain '#'): " + entityId)
    entityId + "#" + Try(entityTypeId(entityType)).getOrElse(entityType)
  }

  private def entityRegion(entityTypeToken: String): ActorRef = {
    Try(actorTypeToRegions(entityTypeToken.toInt)).getOrElse(
      ClusterSharding(context.system).shardRegion(entityTypeToken)
    )
  }

  private def entityTypeId(entityType: String): Int = {
    entityTypeMappings.find(e => e._2 == entityType).map(_._1)
      .getOrElse(throw new RuntimeException("entity type mapping not found for type: " + entityType))
  }

  private lazy val scheduledJobInterval: Int = appConfig.getIntOption(
    s"$AGENT_ACTOR_WATCHER_SCHEDULED_JOB_INTERVAL_IN_SECONDS")
    .getOrElse(200)

  private lazy val batchSize: Int = appConfig.getIntOption(ITEM_WATCHER_BATCH_SIZE).getOrElse(100)

  private lazy val entityTypeMappings = EntityTypeMapper.buildEntityTypeMappings(appConfig)
  private lazy val actorTypeToRegions = EntityTypeMapper.buildRegionMappings(appConfig, context.system)

  private def activeRegisteredItemMetricsName: String =
    s"as.akka.actor.$itemManagerEntityId.retry.active.count"
  private def pendingActiveRegisteredItemMetricsName: String =
    s"as.akka.actor.$itemManagerEntityId.retry.pending.count"

  scheduleJob(
    "CheckForPeriodicTaskExecution",
    scheduledJobInterval,
    CheckForPeriodicTaskExecution
  )

  setItemManagerConfig()

  private def fetchAllActiveItems(): Unit = {
    val fut = itemManagerRegion ? ForIdentifier(itemManagerEntityId, ExternalCmdWrapper(GetItems(Set(ITEM_STATUS_ACTIVE)), None))
    fut map {
      case ai: AllItems => self ! FetchedActiveItems(ai.items)
      case NoItemsFound => self ! FetchedActiveItems(Map.empty)
    }
  }

  private def updateFetchedItems(fai: FetchedActiveItems): Unit = {
    activeItems = activeItems ++ fai.items
    metricsWriter.gaugeUpdate(activeRegisteredItemMetricsName, activeItems.size)
    processOneBatchOfFetchedActiveItems()
  }

  private def processOneBatchOfFetchedActiveItems(): Unit = {
    if (activeItems.nonEmpty) {
      getBatchedRecords.foreach { case (itemId, _) =>
        sendMsgToWatchedItem(itemId)
        activeItems = activeItems - itemId
      }
      metricsWriter.gaugeUpdate(pendingActiveRegisteredItemMetricsName, activeItems.size)
    }
  }

  private def getBatchedRecords: Map[ItemId, ItemDetail] = {
    if (batchSize >= 0) {
      activeItems.take(batchSize)
    } else activeItems
  }

  var activeItems: Map[ItemId, ItemDetail] = Map.empty
}

case object CheckForPeriodicTaskExecution extends ActorMessage
case class AddItem(itemId: ItemId, itemEntityType: String, detail: Option[String]=None) extends ActorMessage
case class RemoveItem(itemId: ItemId, itemEntityType: String) extends ActorMessage
case class FetchedActiveItems(items: Map[ItemId, ItemDetail]) extends ActorMessage


object ActorWatcher {
  /**
   * item manager entity id PREFIX
   * @return
   */

  lazy val itemManagerEntityIdPrefix: String = "watcher"
  def props(config: AppConfig, ec: ExecutionContext): Props = Props(new ActorWatcher(config, ec))
}

case object CheckWatchedItem extends ActorMessage


case class ForEntityItemWatcher(override val cmd: Any) extends ForWatcherManagerChild
