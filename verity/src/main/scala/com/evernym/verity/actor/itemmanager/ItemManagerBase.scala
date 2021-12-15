package com.evernym.verity.actor.itemmanager

import java.util.UUID

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.actor._
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SnapshotConfig, SnapshotterExt}
import com.evernym.verity.config.AppConfig

import scala.concurrent.{ExecutionContext, Future}


trait ItemManagerBase
  extends BasePersistentActor
    with SnapshotterExt[ItemManagerState]
    with ItemCommandHandlerBase
    with DefaultPersistenceEncryption {

  private implicit def executionContext: ExecutionContext = futureExecutionContext

  implicit def appConfig: AppConfig

  override lazy val snapshotConfig: SnapshotConfig = SnapshotConfig (
    snapshotEveryNEvents = None,
    keepNSnapshots = Option(1),
    deleteEventsOnSnapshot = false
  )

  override def receiveCmdHandler: Receive = {
    if (itemManagerState.isDefined) receiveCmdAfterItemConfigSet
    else receiveCmdBeforeItemConfigSet
  }

  val receiveCmdBeforeItemConfigSet: Receive = {
    case so: SetItemManagerConfig => handleSetItemManagerConfig(so)
    case am: ActorMessage         => unhandledMsg(am, ItemManagerConfigNotYetSet)
  }

  val receiveCmdAfterItemConfigSet: Receive = {

    case _: SetItemManagerConfig                => sender ! ItemManagerConfigAlreadySet

    case GetState                               => sender ! itemManagerStateReq

    case ici: MarkItemContainerAsInitialized    => updateItemContainerInitialized(ici)

    case uhti: UpdateHeadAndOrTailId            => updateItemContainerHeadOrTailAsNeeded(uhti.latestCreatedContainerId)

    case cmd: ContainerMigrated                 => updateHeadIdIfNeeded(cmd)

    case gi: GetItem                            => forwardGetItemToSpecificContainer(gi)

    case si: UpdateItem                         => forwardSaveItemToSpecificContainer(si)

    case cmd: NeedContainerListTraversal        => sendToTaskExecutor(cmd)

    case icr: ItemCmdResponse =>
      icr.msg match {
        case is: ItemUpdated => handleItemUpdated(icr.senderEntityId, is)
      }
  }

  override val receiveEvent: Receive = {
    case x => throw new RuntimeException("no event recovery supported, event received: " + x)
  }

  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case ims: ItemManagerState =>
      val headIdOpt = if (ims.headContainerEntityId == "") None else Option(ims.headContainerEntityId)
      val tailIdOpt = if (ims.tailContainerEntityId == "") None else Option(ims.tailContainerEntityId)
      itemManagerState = Option(ItemManagerStateDetail(
        ims.totalEverAllocatedContainers, headIdOpt, tailIdOpt, ims.migrateItemsToNextLinkedContainer))
      itemManagerState.flatMap(_.tailContainerEntityId).foreach { tailContainerId =>
        addItemToInitializedContainer(tailContainerId)
      }
  }

  var itemManagerState: Option[ItemManagerStateDetail] = None
  var saveItemIdsInProgress: Map[ItemId, ActorRef] = Map.empty
  var initializedItemContainers: Set[ItemContainerEntityId] = Set.empty

  def itemManagerStateReq: ItemManagerStateDetail = itemManagerState.getOrElse(
    throw new RuntimeException("not yet initialized"))

  lazy val itemContainerRegion: ActorRef = ClusterSharding(context.system).shardRegion(ITEM_CONTAINER_REGION_ACTOR_NAME)

  override def snapshotState: Option[ItemManagerState] =
    itemManagerState.map(ims => ItemManagerState(
      ims.totalEverAllocatedContainers, ims.headContainerEntityId.getOrElse(""),
      ims.tailContainerEntityId.getOrElse(""), ims.migrateItemsToNextLinkedContainer))

  def sendResponseToSender(itemContainerEntityId: ItemContainerEntityId, itemId: ItemId, response: Any): Unit = {
    saveItemIdsInProgress.get(itemId).foreach { origSender =>
      origSender ! ItemCmdResponse(response, itemContainerEntityId)
      saveItemIdsInProgress = saveItemIdsInProgress.filterNot(_._1 == itemId)
    }
  }

  def handleItemUpdated(itemContainerEntityId: ItemContainerEntityId, is: ItemUpdated): Unit = {
    try {
      sendResponseToSender(itemContainerEntityId, is.id, is)
    } catch {
      case e: Exception =>
        saveItemIdsInProgress.get(is.id).foreach { origSender =>
          handleException(e, origSender)
        }
    }
  }

  def buildItemContainerEntityId(itemId: ItemId): ItemContainerEntityId = {
    buildItemContainerEntityId(entityId, itemId)
  }

  def recordMetrics(): Unit = {
    itemManagerState.foreach { ims =>
      import com.evernym.verity.observability.metrics.CustomMetrics._
      metricsWriter.gaugeUpdate(AS_USER_AGENT_PAIRWISE_WATCHER_TOTAL_CONTAINER_COUNT,
        ims.totalEverAllocatedContainers, Map(TAG_KEY_ID -> entityId))
    }
  }

  def updateItemContainerHeadOrTailAsNeeded(latestCreatedContainerId: ItemContainerEntityId): Unit = {
    val currentItemManagerState = itemManagerStateReq

    val expectedHeadId = currentItemManagerState.headContainerEntityId.getOrElse(latestCreatedContainerId)
    val expectedTailId = latestCreatedContainerId
    val totalContainers =
      if (! currentItemManagerState.tailContainerEntityId.contains(latestCreatedContainerId))
        currentItemManagerState.totalEverAllocatedContainers + 1
      else currentItemManagerState.totalEverAllocatedContainers

    if (! currentItemManagerState.headContainerEntityId.contains(expectedHeadId) ||
      ! currentItemManagerState.tailContainerEntityId.contains(expectedTailId)) {
      itemManagerState = Option(itemManagerStateReq.copy(
        totalEverAllocatedContainers = totalContainers,
        headContainerEntityId = Option(expectedHeadId),
        tailContainerEntityId = Option(expectedTailId))
      )
      saveUpdatedState()
      recordMetrics()
    }
    sender ! Done
  }

  def setItemContainerConfigIfNotAlreadyDone(itemContainerEntityId: ItemContainerEntityId): Future[Any] = {
    if (initializedItemContainers.contains(itemContainerEntityId) ||
      itemManagerState.flatMap(_.tailContainerEntityId).contains(itemContainerEntityId)) {
      Future.successful(ItemContainerConfigAlreadySet)
    } else {
      val itemManagerState = itemManagerStateReq
      val icc = SetItemContainerConfig(
        entityId, itemManagerState.tailContainerEntityId,
        itemManagerState.migrateItemsToNextLinkedContainer)

      //set the new container with appropriate initial state
      val setupNewContainerFut = {
        val fut = itemContainerRegion ? ForIdentifier(itemContainerEntityId, InternalCmdWrapper(icc))
        fut.flatMap {
          case _: ItemContainerConfigSet | ItemContainerConfigAlreadySet => Future.successful(Done)
        }
      }

      //update current tail container's next container id
      val updateCurrentTailContainerFut = setupNewContainerFut flatMap {
        case Done =>
          itemManagerState.tailContainerEntityId.map { tcid =>
            itemContainerRegion ? ForIdentifier(tcid, InternalCmdWrapper(UpdateNextContainerId(itemContainerEntityId)))
          }.getOrElse(Future.successful(Done))
      }

      //update head and/or tail id in this actor
      val selfUpdateFut = updateCurrentTailContainerFut flatMap {
        case _: ItemContainerNextIdUpdated | Done =>
          self ? InternalCmdWrapper(UpdateHeadAndOrTailId(itemContainerEntityId))
      }

      //mark the new container id as initialized in this actor
      selfUpdateFut flatMap {
        case Done =>
          self ? InternalCmdWrapper(MarkItemContainerAsInitialized(itemContainerEntityId))
      }
    }
  }

  def forwardSaveItemToSpecificContainer(si: UpdateItem): Unit = {
    saveItemIdsInProgress += (si.id -> sender)
    val itemContainerEntityId = buildItemContainerEntityId(si.id)
    val fut = setItemContainerConfigIfNotAlreadyDone(itemContainerEntityId)
    fut.map { _ =>
      itemContainerRegion ! ForIdentifier(itemContainerEntityId, InternalCmdWrapper(si))
    }
  }

  def forwardGetItemToSpecificContainer(gi: GetItem): Unit = {
    val itemContainerEntityId = buildItemContainerEntityId(gi.id)
    val fut = setItemContainerConfigIfNotAlreadyDone(itemContainerEntityId)
    val sndr = sender()
    fut map { _ =>
      itemContainerRegion.tell(ForIdentifier(itemContainerEntityId, InternalCmdWrapper(gi)), sndr)
    }
  }

  def updateHeadIdIfNeeded(cm: ContainerMigrated): Unit = {
    if (itemManagerStateReq.headContainerEntityId.contains(cm.migratedContainerEntityId)) {
      itemManagerState = itemManagerState.map(_.copy(headContainerEntityId = Option(cm.migratedContainersNextEntityId)))
      saveUpdatedState()
    }
    sender ! Done
  }

  def sendToTaskExecutor(cmd: NeedContainerListTraversal): Unit = {
    itemManagerStateReq.headContainerEntityId.zip(itemManagerStateReq.tailContainerEntityId).headOption match {
      case Some((head, tail)) =>
        val taskExecutor = context.actorOf(Props(new ItemManagerTaskExecutor(appConfig, entityId)), UUID.randomUUID().toString)
        taskExecutor.tell(ExecuteAgainstItemContainerLinkedList(head, tail, cmd), sender)
      case None => sender ! NoItemsFound
    }
  }

  def addItemToInitializedContainer(entityId: ItemContainerEntityId): Unit = {
    initializedItemContainers += entityId
  }

  def updateItemContainerInitialized(ici: MarkItemContainerAsInitialized): Unit = {
    addItemToInitializedContainer(ici.entityId)
    sender ! Done
  }

  def handleSetItemManagerConfig(so: SetItemManagerConfig): Unit = {
    itemManagerState = Option(ItemManagerStateDetail(0, None, None, so.migrateItemsToNextLinkedContainer))
    saveUpdatedState()
    sender ! itemManagerStateReq
  }

  def saveUpdatedState(): Unit = {
    saveSnapshotStateIfAvailable()
  }
}

case class UpdateHeadAndOrTailId(latestCreatedContainerId: ItemContainerEntityId) extends ActorMessage
case class ItemManagerStateDetail(totalEverAllocatedContainers: Int,
                                  headContainerEntityId: Option[ItemContainerEntityId],
                                  tailContainerEntityId: Option[ItemContainerEntityId],
                                  migrateItemsToNextLinkedContainer: Boolean) extends ActorMessage

case class ContainerMigrated(migratedContainerEntityId: ItemContainerEntityId, migratedContainersNextEntityId: ItemContainerEntityId) extends ActorMessage
case class MarkItemContainerAsInitialized(entityId: ItemContainerEntityId) extends ActorMessage

case object NoItemsFound extends ActorMessage


trait NeedContainerListTraversal extends ActorMessage
case object GetActiveContainers extends NeedContainerListTraversal
case class GetItems(filterByStatutes: Set[Int]) extends NeedContainerListTraversal