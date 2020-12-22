package com.evernym.verity.actor.cluster_singleton.watcher

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.cluster_singleton.ForWatcherManager
import com.evernym.verity.actor.itemmanager.ItemCommonConstants._
import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemId, ItemManagerEntityId, ItemType}
import com.evernym.verity.actor.itemmanager._
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, ForIdentifier}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.ActorErrorResp
import com.evernym.verity.actor.base.BaseNonPersistentActor
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future


/**
 * this is parent for any watcher manager actor we create
 * (as of today there is only one such watcher manager called 'uap-actor-watcher' [uap = user agent pairwise])
 * @param appConfig application configuration
 * @param childActorDetails child actor to be created by this Watcher Manager actor
 */
class WatcherManager(val appConfig: AppConfig, val childActorDetails: Set[WatcherChildActorDetail]) extends BaseNonPersistentActor {
  val logger: Logger = getLoggerByClass(classOf[WatcherManager])

  childActorDetails.foreach { cad =>
    val enabled = appConfig.getConfigBooleanOption(cad.enabledConfName).getOrElse(true)
    logger.debug("watcher manager child actor detail " + enabled)
    if (enabled) {
      logger.debug(s"${cad.actorName} child actor is enabled to be created")
      getRequiredActor(cad.actorName, cad.actorProp)
    }
  }

  def getRequiredActor(name: String, props: Props): ActorRef = context.child(name).getOrElse(context.actorOf(props, name))

  def forwardToChild(actorName: String, cmd: Any): Unit = {
    childActorDetails.find(_.actorName == actorName).foreach { childActorDetail =>
      val enabled = appConfig.getConfigBooleanOption(childActorDetail.enabledConfName).getOrElse(true)
      if (enabled) {
        val actorRef = getRequiredActor(childActorDetail.actorName, childActorDetail.actorProp)
        actorRef forward cmd
      }
    }
  }

  override def receiveCmd: Receive = {
    case fwm: ForWatcherManager => forwardToChild(fwm.getActorName, fwm.cmd)
  }
}


trait WatcherBase extends BaseNonPersistentActor with HasActorResponseTimeout with HasAppConfig {
  implicit def appConfig: AppConfig

  val logger: Logger = getLoggerByClass(classOf[WatcherBase])

  override def receiveCmd: Receive = {
    case CheckForPeriodicTaskExecution  => handlePeriodicTaskExecution()
    case ai: AddItem                    => addItem(ai)
    case ri: RemoveItem                 => removeItem(ri)
    case fai: FetchedActiveItems        => updateFetchedItems(fai)
    case ItemManagerConfigAlreadySet    => //this will be received from item manager if it is already configured
    case _: ItemManagerStateDetail      => //this will be received from item manager if it got configured for first time
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
   * type of item being watched
   * this is only used to find correct item container mapper version
   * see 'buildItemContainerEntityId' method in 'ItemConfigManager'
   * @return
   */
  def itemType: ItemType


  def ownerVerKey: Option[VerKey]=None

  /**
   * item manager entity id which will be used by this watcher actor to send messages like save item, get item etc.
   * @return
   */
  def itemManagerEntityId: ItemManagerEntityId

  /**
   * configuration which decides if items should be migrated to next linked container or not.
   * @return
   */
  def migrateItemsToNextLinkedContainer: Boolean

  /**
   * configuration which decides if items should be migrated to latest versioned containers or not.
   * say initial version was v1 and for any reason if we want to introduce new version
   * and we want old container's active items to be migrated to new containers (as per new version)
   * @return
   */
  def migrateItemsToLatestVersionedContainers: Boolean

  /**
   * should be overridden by implementing class and it should send a message to that item
   * @param itemId item id being watched
   */
  def sendMsgToWatchedItem(itemId: ItemId): Unit

  def buildItemManagerConfig: SetItemManagerConfig = SetItemManagerConfig(
    itemType,
    ownerVerKey,
    migrateItemsToNextLinkedContainer,
    migrateItemsToLatestVersionedContainers)

  lazy val itemManagerRegion: ActorRef = ClusterSharding(context.system).shardRegion(ITEM_MANAGER_REGION_ACTOR_NAME)

  def setItemManagerConfig(): Unit = {
    itemManagerRegion ! ForIdentifier(itemManagerEntityId, ExternalCmdWrapper(buildItemManagerConfig, None))
  }

  def addItem(ai: AddItem): Future[Any] = {
    val uip = UpdateItem(ai.itemId, ai.status, ai.detail, None)
    itemManagerRegion ? ForIdentifier(itemManagerEntityId, ExternalCmdWrapper(uip, None))
  }

  def removeItem(ri: RemoveItem): Future[Any] = {
    val uip = UpdateItem(ri.itemId, Option(ITEM_STATUS_REMOVED), None, None)
    itemManagerRegion ? ForIdentifier(itemManagerEntityId, ExternalCmdWrapper(uip, None))
  }

  lazy val scheduledJobInterval: Int = appConfig.getConfigIntOption(
    USER_AGENT_PAIRWISE_WATCHER_SCHEDULED_JOB_INTERVAL_IN_SECONDS).getOrElse(200)

  lazy val batchSize: Int = appConfig.getConfigIntOption(ITEM_WATCHER_BATCH_SIZE).getOrElse(100)

  scheduleJob(
    "CheckForPeriodicTaskExecution",
    scheduledJobInterval,
    CheckForPeriodicTaskExecution
  )

  setItemManagerConfig()

  def fetchAllActiveItems(): Unit = {
    val fut = itemManagerRegion ? ForIdentifier(itemManagerEntityId, ExternalCmdWrapper(GetItems(Set(ITEM_STATUS_ACTIVE)), None))
    fut map {
      case ai: AllItems => self ! FetchedActiveItems(ai.items)
      case NoItemsFound => self ! FetchedActiveItems(Map.empty)
    }
  }

  def updateFetchedItems(fai: FetchedActiveItems): Unit = {
    activeItems = activeItems ++ fai.items
    MetricsWriter.gaugeApi.updateWithTags(activeRegisteredItemMetricsName, activeItems.size)
    processOneBatchOfFetchedActiveItems()
  }

  def processOneBatchOfFetchedActiveItems(): Unit = {
    if (activeItems.nonEmpty) {
      getBatchedRecords.foreach { case (itemId, _) =>
        sendMsgToWatchedItem(itemId)
        activeItems = activeItems - itemId
      }
      MetricsWriter.gaugeApi.updateWithTags(pendingActiveRegisteredItemMetricsName, activeItems.size)
    }
  }

  def getBatchedRecords: Map[ItemId, ItemDetail] = {
    if (batchSize >= 0) {
      activeItems.take(batchSize)
    } else activeItems
  }

  var activeItems: Map[ItemId, ItemDetail] = Map.empty

  def activeRegisteredItemMetricsName: String
  def pendingActiveRegisteredItemMetricsName: String
}

case object CheckForPeriodicTaskExecution extends ActorMessageObject
case class AddItem(itemId: ItemId, status: Option[Int], detail: Option[String]) extends ActorMessageClass
case class RemoveItem(itemId: ItemId) extends ActorMessageClass
case class FetchedActiveItems(items: Map[ItemId, ItemDetail]) extends ActorMessageClass
case class WatcherChildActorDetail(enabledConfName: String, actorName: String, actorProp: Props)

object WatcherManager {
  val name: String = WATCHER_MANAGER
  def props(appConfig: AppConfig, childActorDetails: Set[WatcherChildActorDetail]): Props =
    Props(classOf[WatcherManager], appConfig, childActorDetails)
}
