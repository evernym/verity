package com.evernym.verity.actor.cluster_singleton.watcher

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.{ActorRef, Props, typed}
import akka.cluster.sharding.ClusterSharding
import akka.actor.typed.scaladsl.adapter._
import com.evernym.verity.actor.cluster_singleton.ForWatcherManagerChild
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, HasAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.actor.agent.EntityTypeMapper
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.item_store.{ItemId, ItemStore}
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

  implicit val executionContext = ec

  val logger: Logger = getLoggerByClass(classOf[ActorWatcher])

  override def receiveCmd: Receive = {
    case ai: AddItem                    => addItem(ai)
    case ri: RemoveItem                 => removeItem(ri)
    case GetItems                       => getItems()
    case fai: FetchedActiveItems        => updateFetchedItems(fai)
    case CheckForPeriodicTaskExecution  => handlePeriodicTaskExecution()
    case _: ItemStore.Reply             => //nothing to do
    case ar: ActorErrorResp             => logger.error("received unexpected message: " + ar)
  }

  def handlePeriodicTaskExecution(): Unit = {
    //fetch active items only when it has processed previously fetched active items
    if (activeItems.isEmpty) {
      fetchAllActiveItems()
    }
    processOneBatchOfFetchedActiveItems()
  }

  private def addItem(ai: AddItem): Unit = {
    val itemId = buildUniqueItemId(ai.itemId, ai.itemEntityType)
    itemStore ! ItemStore.Commands.AddItem(itemId, "", context.self)
  }

  private def removeItem(ri: RemoveItem): Unit = {
    val itemId = buildUniqueItemId(ri.itemId, ri.itemEntityType)
    itemStore ! ItemStore.Commands.RemoveItem(itemId, context.self)
  }

  private def getItems(): Unit = {
    itemStore ! ItemStore.Commands.Get(sender())
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
    s"as.akka.actor.$itemStoreEntityId.retry.active.count"
  private def pendingActiveRegisteredItemMetricsName: String =
    s"as.akka.actor.$itemStoreEntityId.retry.pending.count"

  scheduleJob(
    "CheckForPeriodicTaskExecution",
    scheduledJobInterval,
    CheckForPeriodicTaskExecution
  )

  private def fetchAllActiveItems(): Unit = {
    val fut = itemStore.ask(ref => ItemStore.Commands.Get(ref))
    fut map { ai: ItemStore.Replies.Items =>
      self ! FetchedActiveItems(ai.active.map(_.id))
    }
  }

  private def updateFetchedItems(fai: FetchedActiveItems): Unit = {
    activeItems = activeItems ++ fai.items
    metricsWriter.gaugeUpdate(activeRegisteredItemMetricsName, activeItems.size)
    processOneBatchOfFetchedActiveItems()
  }

  private def processOneBatchOfFetchedActiveItems(): Unit = {
    if (activeItems.nonEmpty) {
      getBatchedRecords.foreach { itemId =>
        sendMsgToWatchedItem(itemId)
        activeItems = activeItems.filter(_ != itemId)
      }
      metricsWriter.gaugeUpdate(pendingActiveRegisteredItemMetricsName, activeItems.size)
    }
  }

  private def getBatchedRecords: List[ItemId] = {
    if (batchSize >= 0) {
      activeItems.take(batchSize)
    } else activeItems
  }

  var activeItems: List[ItemId] = List.empty

  val itemStoreEntityId = "item-store"
  val itemStore: typed.ActorRef[ItemStore.Cmd] = context.spawn(ItemStore(itemStoreEntityId, appConfig.config), "ItemStore")
  implicit val typedSystem: ActorSystem[_] = context.system.toTyped
}

case object CheckForPeriodicTaskExecution extends ActorMessage
case object GetItems extends ActorMessage
case class AddItem(itemId: ItemId, itemEntityType: String) extends ActorMessage
case class RemoveItem(itemId: ItemId, itemEntityType: String) extends ActorMessage
case class FetchedActiveItems(items: List[ItemId]) extends ActorMessage


object ActorWatcher {
  /**
   * item manager entity id PREFIX
   * @return
   */

  def props(config: AppConfig, ec: ExecutionContext): Props = Props(new ActorWatcher(config, ec))
}

case object CheckWatchedItem extends ActorMessage


case class ForEntityItemWatcher(override val cmd: Any) extends ForWatcherManagerChild
