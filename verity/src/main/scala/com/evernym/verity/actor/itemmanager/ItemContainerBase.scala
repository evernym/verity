package com.evernym.verity.actor.itemmanager

import java.time.ZonedDateTime
import akka.actor.ActorRef
import akka.cluster.sharding.ClusterSharding
import akka.event.Logging.DebugLevel
import akka.pattern.ask
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.actor._
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.itemmanager.ItemCommonConstants._
import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemId, _}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.util.TimeZoneUtil._

import scala.concurrent.Future
import scala.util.{Failure, Success}


trait ItemContainerBase
  extends BasePersistentActor
    with ItemCommandHandlerBase
    with DefaultPersistenceEncryption {

  import context._
  implicit def appConfig: AppConfig

  override def receiveCmdHandler: Receive = {
    val conditionalReceiveCmd = if (itemContainerConfig.isEmpty) receiveCmdBeforeItemConfigSet
    else receiveCmdAfterItemConfigSet

    conditionalReceiveCmd orElse receiveCmdBasic orElse receiveCmdInternal
  }

  val receiveCmdBeforeItemConfigSet: Receive = {
    case so: SetItemContainerConfig => handleSetItemContainerConfig(so)

    case cs: CleanStorage =>
      //if after storage gets cleaned up, but if this actor is not able to send the response back
      // to requester (who requested to clean storage), in that case, next time it will receive 'CleanStorage'
      //in this receiver and thats why this is handled here
      sendContainerStorageCleaned(cs.requestedByItemContainerId)

    case mcsc: MigratedContainerStorageCleaned =>
      //if the requesting container also had all events cleaned up, then, it should just ignore this message
      logMsg(s"received $mcsc in non configured state (mostly this would be " +
        "stale container who already cleaned up its storage), this will be ignored", DebugLevel)

    case am: ActorMessage => unhandledMsg(am, ItemContainerStaleOrConfigNotYetSet)
  }

  val receiveCmdAfterItemConfigSet: Receive = {
    case si: UpdateItem  => saveItem(si)
  }

  val receiveCmdInternal: Receive = {

    case _: SetItemContainerConfig              => sender ! ItemContainerConfigAlreadySet

    case GetState                               => sendState()

    case CheckForPeriodicTaskExecution          => performPeriodicChecks()

    case sim: SaveItemFromMigration             => saveItemFromMigration(sim)

    case upci: UpdatePrevContainerId            => updatePrevContainerId(upci)

    case unci: UpdateNextContainerId            => updateNextContainerId(unci)

    case mi: MigrateItems                       => migrateItemsToContainer(mi)

    case mcbc: MigrationCompletedByContainer    => migrationCompletedByContainer(mcbc)

    case mf: MigrationFinished                  => handleMigrationFinished(mf)

    case cs: CleanStorage                       => handleCleanStorage(cs)

    case mcsc: MigratedContainerStorageCleaned  => migratedContainerStorageCleaned(mcsc)

    case eaf: ExecuteAndForwardReq              => executeAndForwardReqToNextContainer(eaf)

    case icr: ItemCmdResponse =>
      icr.msg match {
        case im: ItemMigrated =>
          if (! migratedItems.exists(mi => mi.id == im.itemId && mi.toContainerEntityId == im.toContainerEntityId))
            writeAndApply(im)
      }
  }

  override val receiveEvent: Receive = {

    case imcs: ItemContainerConfigSet =>
      itemContainerConfig = Option(ItemContainerConfig(imcs.managerEntityId, imcs.migrateItemsToNextLinkedContainer))

    case piu: ItemContainerPrevIdUpdated =>
      val prevIdOpt = if (piu.id == NO_CONTAINER_ID) None else Option(piu.id)
      itemContainerLink = Option(getExistingOrNewItemContainerLink.copy(prevId = prevIdOpt))

    case niu: ItemContainerNextIdUpdated =>
      val nextIdOpt = if (niu.id == NO_CONTAINER_ID) None else Option(niu.id)
      itemContainerLink = Option(getExistingOrNewItemContainerLink.copy(nextId = nextIdOpt))

    case iu: ItemUpdated =>
      val detail = if (iu.detail == "") None else Option(iu.detail)
      items += (iu.id -> ItemDetail(iu.status, detail, iu.isFromMigration, getUTCZonedDateTimeFromMillis(iu.creationTimeInMillis)))

    case im: ItemMigrated =>
      val item = items(im.itemId)
      items += (im.itemId -> item.copy(status = ITEM_STATUS_MIGRATED))
      migratedItems = migratedItems + MigratedItemDetail(im.itemId, im.toContainerEntityId)

    case ms: MigrationStarted =>
      migrationStatus = migrationStatus +
        (ms.toContainerEntityId -> MigrationStatus(startedAt = Option(getUTCZonedDateTimeFromMillis(ms.time)), finishedAt = None))

    case mf: MigrationFinished =>
      migrationStatus.get(mf.toContainerEntityId).foreach { ms =>
        migrationStatus = migrationStatus +
          (mf.toContainerEntityId -> ms.copy(finishedAt = Option(getUTCZonedDateTimeFromMillis(mf.time))))
      }

    case mcbc: MigrationCompletedByContainer =>
      migratedContainers = migratedContainers + (mcbc.entityId -> MigratedContainer(getUTCZonedDateTimeFromMillis(mcbc.time), isStorageCleaned = false))

    case mcsc: MigratedContainerStorageCleaned =>
      migratedContainers.get(mcsc.entityId).foreach { mc =>
        migratedContainers = migratedContainers + (mcsc.entityId -> mc.copy(isStorageCleaned = true))
      }
  }


  /**
   * map of item id and item details
   */
  var items: Map[ItemId, ItemDetail] = Map.empty

  /**
   * status of migration check results (this is mostly to help troubleshooting issues)
   * this is NON persisted state (and controlled how many past records can be seen by a configuration)
   */
  var migrationCheckResults: List[MigrationCheckResult] = List.empty

  /**
   * all migrated items (item id and container id where it migrated)
   */
  var migratedItems: Set[MigratedItemDetail] = Set.empty

  /**
   * map of container entity id (where items are being migrated) and its migration status (started and finished time)
   */
  var migrationStatus: Map[ItemContainerEntityId, MigrationStatus] = Map.empty

  /**
   * map of container ids who completed migrating their active items to this container
   */
  var migratedContainers: Map[ItemContainerEntityId, MigratedContainer] = Map.empty

  var cleanStorageRequestedByContainerIds: Set[ItemContainerEntityId] = Set.empty

  /**
   * link describing current container's previous and next container entity id
   */
  var itemContainerLink: Option[ItemContainerLink] = None

  var itemContainerConfig: Option[ItemContainerConfig] = None

  lazy val itemContainerRegion: ActorRef = ClusterSharding(context.system).shardRegion(ITEM_CONTAINER_REGION_ACTOR_NAME)

  lazy val itemManagerRegion: ActorRef = ClusterSharding(context.system).shardRegion(ITEM_MANAGER_REGION_ACTOR_NAME)

  lazy val scheduledJobInterval: Int = appConfig.getConfigIntOption(
    ITEM_CONTAINER_SCHEDULED_JOB_INTERVAL_IN_SECONDS).getOrElse(300)
  lazy val itemMigrationChunkSize: Int = appConfig.getConfigIntOption(
    ITEM_CONTAINER_MIGRATION_CHUNK_SIZE).getOrElse(20)
  lazy val itemMigrationCheckResultHistorySize: Int = appConfig.getConfigIntOption(
    ITEM_CONTAINER_MIGRATION_CHECK_RESULT_HISTORY_SIZE).getOrElse(20)
  //determines after how many tries scheduled job should be stopped if there is no more work.
  val disableScheduleJobAfterTriesWithoutWork = 20

  var scheduledJobId: Option[JobId] = None
  scheduleJobIfNotAlreadyScheduled()

  def stopPeriodicJob(): Unit = {
    scheduledJobId.foreach(stopScheduledJob)
    scheduledJobId = None
    logMsg("scheduled job stopped", DebugLevel)
  }

  def stopThisActor(): Unit = {
    logMsg("actor about to stop", DebugLevel)
    stopPeriodicJob()
    stopActor()
  }

  def scheduleJobIfNotAlreadyScheduled(): Unit = {
    if (scheduledJobId.isEmpty) {
      logMsg("scheduled job started", DebugLevel)
      val jobId = "CheckForPeriodicTaskExecution"
      scheduleJob(
        jobId,
        scheduledJobInterval,
        InternalCmdWrapper(CheckForPeriodicTaskExecution)
      )
      scheduledJobId = Option(jobId)
    }
  }

  override def preMsgHandler(msg: Any): Unit = {
    scheduleJobIfRequired(msg)
  }

  /**
   * based on the incoming msg it makes sure that scheduled job is started (if it not already started)
   * @param msg incoming message
   */
  def scheduleJobIfRequired(msg: Any): Unit = {
    msg match {
      case _ @ (_:UpdateItem |
                _:SaveItemFromMigration |
                _:UpdateNextContainerId |
                _:UpdatePrevContainerId |
                _: MigrationCompletedByContainer)
                              => scheduleJobIfNotAlreadyScheduled()
      case _                  => //nothing to do
    }
  }

  override def validateMsg(msg: Any): Boolean = {
    checkTargetEntityNotSelf(msg)
  }

  /**
   * checks if incoming msg is supposed to be handled by this container or not
   * @param msg incoming message
   * @return
   */
  def checkTargetEntityNotSelf(msg: Any): Boolean = {
    val targetEntityId = msg match {
      case upc: UpdatePrevContainerId               => Some(upc.id)
      case unc: UpdateNextContainerId               => Some(unc.id)
      case mi: MigrateItems                         => mi.toContainerEntityIdOpt
      case mcbc: MigrationCompletedByContainer      => Some(mcbc.entityId)
      case cs: CleanStorage                         => Some(cs.requestedByItemContainerId)
      case mcsc: MigratedContainerStorageCleaned    => Some(mcsc.entityId)
      case _                                        => None
    }

    if (targetEntityId.contains(entityId)) {
      handleUnsupportedCondition(s"$entityId received unsupported command with target entity id as self: $msg")
      false
    } else true
  }

  def getActiveItemsOnly: Map[ItemId, ItemDetail] = items.filter(_._2.status == ITEM_STATUS_ACTIVE)

  def getItemContainerConfigReq: ItemContainerConfig = itemContainerConfig.getOrElse(
    throw new RuntimeException("item container config not yet set"))

  def getItemContainerLinkReq: ItemContainerLink = itemContainerLink.getOrElse(
    throw new RuntimeException("item container link not yet set"))

  def getContainerId(containerIdOpt: Option[ItemContainerEntityId]): ItemContainerEntityId = containerIdOpt.getOrElse(NO_CONTAINER_ID)

  def isMigrationNotStarted(toContainerIdOpt: Option[ItemContainerEntityId]): Boolean =
    migrationStatus.get(getContainerId(toContainerIdOpt)).forall(ms => ms.startedAt.isEmpty && ms.finishedAt.isEmpty)

  def isMigrationFinished(toContainerId: ItemContainerEntityId): Boolean =
    migrationStatus.get(toContainerId).exists(ms => ms.startedAt.isDefined && ms.finishedAt.isDefined)

  def isMigrationFinished(toContainerIdOpt: Option[ItemContainerEntityId]): Boolean =
    isMigrationFinished(getContainerId(toContainerIdOpt))

  def performPeriodicChecks(): Unit = {
    logMsg("periodic check started", DebugLevel)
    performStorageCleanupForMigratedContainers()
    performItemMigrationIfRequired()
    performAnyPendingCleanup()
    logMsg("periodic check finished", DebugLevel)
  }

  /**
   * delete events of those container which is marked as migrated
   */
  def performStorageCleanupForMigratedContainers(): Unit = {
    containerIdsForPendingStorageCleaning.foreach { migratedContainerEntityId =>
      if (entityId == migratedContainerEntityId) {
        handleUnsupportedCondition(s"$entityId container got in corrupted state as it contains " +
          s"self reference in these migrated containers: $migratedContainers")
      } else {
        sendInternalCmdToItemContainer(CleanStorage(entityId), migratedContainerEntityId)
      }
    }
  }

  def containerIdsForPendingStorageCleaning: Set[ItemContainerEntityId] =
    migratedContainers.filterNot(mc => mc._2.isStorageCleaned).keySet

  def performAnyPendingCleanup(): Unit = {
    if (migrationStatus.nonEmpty && migrationStatus.values.forall(_.finishedAt.isDefined)) {
      //this means, this actor is no longer active (all items are already migrated)
      notifyItemManagerAboutMigrationCompleted(None).map { _ =>
        stopThisActor()
      }
    } else if (
      migrationCheckResults.size == disableScheduleJobAfterTriesWithoutWork
      && migrationCheckResults.forall { mcr =>
      //this means, this actor is not doing anything meaningful
      ! mcr.migrateToLatestVersionedContainers &&
        ! mcr.migrateToNextLinkedContainer &&
        ! mcr.keepProcessingStartedMigrations}) {
      stopPeriodicJob()
    }
  }

  def checkIfMigrationToNextLinkedContainerShallBeStarted(conf: ItemContainerConfig): Boolean = {
    conf.migrateItemsToNextLinkedContainer &&
      //if this container has a link to the next container
      itemContainerLink.exists(_.nextId.isDefined) &&
      //previous container's all items should have been already migrated to current container
      //and previous container's storage should be marked as cleaned
      itemContainerLink.exists(_.prevId.forall{ pid => migratedContainers.exists(e => e._1 == pid && e._2.isStorageCleaned)}) &&
      //migration for next linked container is not already started
      isMigrationNotStarted(itemContainerLink.flatMap(_.nextId))
  }

  def addToMigrationCheckResultHistory(mcr: MigrationCheckResult): Unit = {
    migrationCheckResults = migrationCheckResults.takeRight(itemMigrationCheckResultHistorySize-1) :+ mcr
  }

  def latestMigrationCheckResult(): MigrationCheckResult =
    migrationCheckResults.headOption.map(_.copy(checkedAt = getCurrentUTCZonedDateTime))
      .getOrElse(MigrationCheckResult(getCurrentUTCZonedDateTime))

  def performItemMigrationIfRequired(): Unit = {
    itemContainerConfig.foreach { conf =>
      if (checkIfMigrationToNextLinkedContainerShallBeStarted(conf)) {
        //this section is for current container to check and migrate items to next linked container (it may not be the latest one) if required
        self ! InternalCmdWrapper(MigrateItems(itemContainerLink.flatMap(_.nextId)))
        addToMigrationCheckResultHistory(latestMigrationCheckResult().copy(
          migrateToNextLinkedContainer=true,
          detail=Option(s"sent 'MigrateItems(${itemContainerLink.flatMap(_.nextId)})' to self")
        ))
      } else {
        //this section is to keep processing unfinished migrations
        val runningMigrations = migrationStatus.filterNot(ms => ms._2.finishedAt.isDefined)
        if (runningMigrations.nonEmpty) {
          runningMigrations.foreach { ms =>
            val toItemContainerEntityIdOpt = if (ms._1 == NO_CONTAINER_ID) None else Option(ms._1)
            self ! InternalCmdWrapper(MigrateItems(toItemContainerEntityIdOpt))
            addToMigrationCheckResultHistory(latestMigrationCheckResult().copy(
              keepProcessingStartedMigrations = true,
              detail=Option(s"sent 'MigrateItems($toItemContainerEntityIdOpt)' to self")
            ))
          }
        } else {
          addToMigrationCheckResultHistory(latestMigrationCheckResult().copy(
            keepProcessingStartedMigrations = false,
            detail=None
          ))
        }
      }
    }
  }

  def getExistingOrNewItemContainerLink: ItemContainerLink =
    itemContainerLink.getOrElse(ItemContainerLink(None, None))

  /**
   *
   * @param ui update item command
   * @param isFromMigration helps identifying if the item to be saved is coming as part of general request
   *                        or it is due to item container migration
   * @return
   */
  def saveItemCommon(ui: UpdateItem, isFromMigration: Boolean): ItemUpdated = {
    val oldItemDetail = items.get(ui.id)
    val newItemDetail = ItemDetail(ui.status.getOrElse(ITEM_STATUS_ACTIVE), ui.detailOpt, isFromMigration, getCurrentUTCZonedDateTime)
    val itemUpdated = ItemUpdated(
      ui.id,
      newItemDetail.status,                   //status would be always from this new request message
      newItemDetail.detail.getOrElse(""),     //detail would be always from this new request message
      oldItemDetail.map(_.isFromMigration).getOrElse(isFromMigration),
      getMillisFromZonedDateTime(ui.createdAt.getOrElse(getCurrentUTCZonedDateTime)))

    if (! oldItemDetail.exists(_.isSame(newItemDetail))) {
      writeAndApply(itemUpdated)
    }
    itemUpdated
  }

  def saveItem(si: UpdateItem): Unit = {
    val itemUpdated = saveItemCommon(si, isFromMigration=false)
    sender ! ItemCmdResponse(itemUpdated, entityId)
  }

  def saveItemFromMigration(sim: SaveItemFromMigration): Unit = {
    logMsg("save item from migration: " + sim, DebugLevel)
    if (!items.contains(sim.uip.id)) {
      saveItemCommon(sim.uip, isFromMigration=true)
    }
    sender ! InternalCmdWrapper(ItemCmdResponse(ItemMigrated(sim.uip.id, entityId), entityId))
  }

  def buildItemContainerEntityId(itemId: ItemId): ItemContainerEntityId = {
    ItemConfigManager.buildItemContainerEntityId(getItemContainerConfigReq.managerEntityId, itemId, appConfig)
  }

  def handleItemFound(gi: GetItem, id: ItemDetail): Unit = {
    sender ! ItemCmdResponse(ItemDetailResponse(gi.id, id.status, id.isFromMigration, id.detail), entityId)
  }

  def getItemContainerIdToBeQueriedForItemsNotFound(itemId: ItemId): Option[ItemContainerEntityId] = {
    itemContainerConfig match {
      case Some(c) if c.migrateItemsToNextLinkedContainer && itemContainerLink.exists(_.prevId.isDefined) =>
        getItemContainerLinkReq.prevId
      case _ => None
    }
  }

  def handleItemNotFound(gi: GetItem): Unit = {
    getItemContainerIdToBeQueriedForItemsNotFound(gi.id) match {
      case Some(itemContainerId)=>
        forwardInternalCmdToItemContainer(gi, itemContainerId)
      case _ =>
        sender ! ItemCmdResponse(ItemNotFound(gi.id), entityId)
    }
  }

  def startMigrationIfNotAlreadyStarted(toContainerEntityIdOpt: Option[ItemContainerEntityId]): Unit = {
    if (isMigrationNotStarted(toContainerEntityIdOpt)) {
      writeAndApply(MigrationStarted(getContainerId(toContainerEntityIdOpt), getMillisForCurrentUTCZonedDateTime))
    }
  }

  def candidateForNextContainerId (toContainerEntityIdOpt: Option[ItemContainerEntityId]): Option[ItemContainerEntityId] =
    toContainerEntityIdOpt orElse itemContainerLink.flatMap(_.nextId)

  def notifyItemManagerAboutMigrationCompleted(toContainerEntityIdOpt: Option[ItemContainerEntityId]): Future[Any] = {
    candidateForNextContainerId(toContainerEntityIdOpt).map { nextContainerId =>
      val cmd = InternalCmdWrapper(ContainerMigrated(entityId, nextContainerId))
      itemManagerRegion ? ForIdentifier(getItemContainerConfigReq.managerEntityId, cmd)
    }.getOrElse(Future.successful(Done))
  }

  def notifyNextLinkedContainerAboutMigrationCompleted(toContainerEntityIdOpt: Option[ItemContainerEntityId]): Future[Any] = {
    toContainerEntityIdOpt.map { tcid =>
      askInternalCmdToItemContainer(MigrationCompletedByContainer(entityId, getMillisForCurrentUTCZonedDateTime), tcid)
    }.getOrElse(Future.successful(Done))
  }

  def finishMigrationProcess(toContainerEntityIdOpt: Option[ItemContainerEntityId]): Unit = {
    val migrateToContainerId = candidateForNextContainerId(toContainerEntityIdOpt)

    val updateLinkedListFut = (itemContainerLink.flatMap(_.prevId), migrateToContainerId) match {

      //first container got migrated
      case (None, Some(nextContainerEntityId)) =>
        //let next linked container to update its prev link point to nothing
        askInternalCmdToItemContainer(UpdatePrevContainerId(NO_CONTAINER_ID), nextContainerEntityId)

      //middle container got migrated
      case (Some(previousContainerEntityId), Some(nextContainerEntityId)) =>
        val fut1 = askInternalCmdToItemContainer(UpdateNextContainerId(nextContainerEntityId), previousContainerEntityId)
        val fut2 = askInternalCmdToItemContainer(UpdatePrevContainerId(previousContainerEntityId), nextContainerEntityId)
        Future.sequence(Seq(fut1, fut2))

      //based on current implementation, there should not be a time when last container is getting migrated
      case _ => Future.successful(Done) //nothing to do
    }

    val notifyItemManagerAboutMigrationComplete = notifyItemManagerAboutMigrationCompleted(toContainerEntityIdOpt)
    val notifyNextLinkedContainerAboutMigrationComplete = notifyNextLinkedContainerAboutMigrationCompleted(toContainerEntityIdOpt)
    val allFutures = Future.sequence(Seq(
      //the moment this below future 'updateLinkedListFut' is completed, this current item container is no more accessible
      //via traversing the linked list (except by using next linked container's state)
      updateLinkedListFut,

      notifyNextLinkedContainerAboutMigrationComplete,
      notifyItemManagerAboutMigrationComplete))

    allFutures.onComplete {
      case Success(_) =>
        self ! InternalCmdWrapper(MigrationFinished(getContainerId(toContainerEntityIdOpt), getMillisForCurrentUTCZonedDateTime))
      case Failure(x) =>
        logger.error("error occurred during finishing item container migration: " + x.toString)
    }
  }

  def migrateItemsToContainer(mi: MigrateItems): Unit = {
    logMsg("migrate items received: " + mi, DebugLevel)
    if (isMigrationNotStarted(mi.toContainerEntityIdOpt) || !isMigrationFinished(mi.toContainerEntityIdOpt)) {
      startMigrationIfNotAlreadyStarted(mi.toContainerEntityIdOpt)
      val candidates = getActiveItemsOnly.take(itemMigrationChunkSize)
      if (candidates.nonEmpty) {
        logMsg("few items will be migrated", DebugLevel)
        candidates.foreach { case (itemId, itemDetail) =>
          val newContainerEntityId = mi.toContainerEntityIdOpt.getOrElse(buildItemContainerEntityId(itemId))
          sendInternalCmdToItemContainer(SaveItemFromMigration(UpdateItem(itemId,
            Option(itemDetail.status), itemDetail.detail, Option(itemDetail.createdAt))), newContainerEntityId)
        }
      } else {
        logMsg("no more items for migration, will mark this migration as finished", DebugLevel)
        finishMigrationProcess(mi.toContainerEntityIdOpt)
      }
    }
  }

  val receiveCmdBasic: Receive = {
    case gi: GetItem =>
      items.get(gi.id) match {
        case Some(v) => handleItemFound(gi, v)
        case None    => handleItemNotFound(gi)
      }
  }

  def updatePrevContainerId(upci: UpdatePrevContainerId): Unit = {
    logMsg("update prev container id received: " + upci, DebugLevel)
    val event = ItemContainerPrevIdUpdated(upci.id)
    if (itemContainerLink.forall(!_.prevId.contains(upci.id))) {
      writeAndApply(event)
    }
    sender ! event
  }

  def updateNextContainerId(unci: UpdateNextContainerId): Unit = {
    logMsg("update next container id received: " + unci, DebugLevel)
    val event = ItemContainerNextIdUpdated(unci.id)
    if (itemContainerLink.forall(!_.nextId.contains(unci.id))) {
      writeAndApply(event)
    }
    sender ! event
  }

  def executeAndForwardReqToNextContainer(eaf: ExecuteAndForwardReq): Unit = {
    val respFromCurrentContainer = eaf.cmd match {
      case GetContainerStatus   =>
        val containerStatus = items.values.groupBy(_.status).mapValues(_.size)
        val containerStatusResp = containerStatus.map(r => getStatusString(r._1) -> r._2)
        ContainerStatus(entityId, containerStatusResp)
      case gai: GetItems        =>
        val allItems = items.filter(i => gai.filterByStatutes.isEmpty || gai.filterByStatutes.contains(i._2.status))
        ContainerItems(allItems)
    }
    sender ! ExecuteAndForwardResp(eaf.containerSequenceId, entityId, respFromCurrentContainer)
    itemContainerLink.flatMap(_.nextId).foreach { nextContainerId =>
      val cmd = InternalCmdWrapper(eaf.copy(containerSequenceId = eaf.containerSequenceId + 1))
      itemContainerRegion.tell(ForIdentifier(nextContainerId, cmd), sender)
    }
  }

  def sendState(): Unit = {
    sender ! ItemContainerState(itemContainerConfig, itemContainerLink, items,
      migratedItems, migrationStatus, migratedContainers,
      ScheduledJobDetail(scheduledJobId.isDefined, migrationCheckResults))
  }

  def migrationCompletedByContainer(mcbc: MigrationCompletedByContainer): Unit = {
    logMsg("migration completed by container received: " + mcbc, DebugLevel)
    writeAndApply(mcbc)
    sender ! Done
  }

  def migratedContainerStorageCleaned(mcsc: MigratedContainerStorageCleaned): Unit = {
    logMsg("migrated container storage cleaned received: " + mcsc, DebugLevel)
    if (! migratedContainers.contains(mcsc.entityId)) {
      val msg = s"container $entityId requested to mark ${mcsc.entityId} container's storage as cleaned " +
        "before it is marked as migration completed"
      handleUnsupportedCondition(msg)
    } else {
      writeAndApply(mcsc)
    }
  }

  def handleCleanStorage(cs: CleanStorage): Unit = {
    logMsg("clean storage received: " + cs, DebugLevel)
    val pendingStorageCleanup = containerIdsForPendingStorageCleaning
    (pendingStorageCleanup.isEmpty, isMigrationFinished(cs.requestedByItemContainerId)) match {
      case (true, true) =>
        cleanStorageRequestedByContainerIds = cleanStorageRequestedByContainerIds + cs.requestedByItemContainerId
        deleteMessagesExtended(lastSequenceNr)    //this will delete all events of this actor
      case (true, false) =>
        logMsg("clean storage won't be processed as migration is not started or not yet marked as finished", DebugLevel)
      case (false, _)   =>
        logMsg(s"clean storage won't be processed as the same is pending for ${pendingStorageCleanup.mkString(", ")} container(s)", DebugLevel)
    }
  }

  override def postAllMsgsDeleted(): Unit = {
    cleanStorageRequestedByContainerIds.foreach { cleanupRequestedEntityId =>
      sendContainerStorageCleaned(cleanupRequestedEntityId)
    }
  }

  /**
   * sends MigratedContainerStorageCleaned to the item container who initially requested this
   * item container to clean its storage
   * one of the last action of this method should be to change receiver
   * @param toEntityId item container id who requested this item container to clean its storage
   */
  def sendContainerStorageCleaned(toEntityId: ItemContainerEntityId): Unit = {
    sendInternalCmdToItemContainer(MigratedContainerStorageCleaned(entityId), toEntityId)
    setNewReceiveBehaviour(receiveCmdAfterSuccessfulStorageCleanup)
    stopThisActor()
  }

  def handleMigrationFinished(mf: MigrationFinished): Unit = {
    logMsg("migration finished received: " + mf, DebugLevel)
    writeAndApply(mf)
  }

  def handleSetItemContainerConfig(so: SetItemContainerConfig): Unit = {
    val prevContainerEntityId = getContainerId(so.prevContainerEntityId)
    writeAndApply(ItemContainerPrevIdUpdated(prevContainerEntityId))
    writeApplyAndSendItBack(ItemContainerConfigSet(so.managerEntityId, so.migrateItemsToNextLinkedContainer))
  }

  def sendInternalCmdToItemContainer(cmd: Any, toEntityId: ItemContainerEntityId): Unit = {
    itemContainerRegion ! ForIdentifier(toEntityId, InternalCmdWrapper(cmd))
  }

  def forwardInternalCmdToItemContainer(cmd: Any, toEntityId: ItemContainerEntityId): Unit = {
    itemContainerRegion.forward(ForIdentifier(toEntityId, InternalCmdWrapper(cmd)))
  }

  def askInternalCmdToItemContainer(cmd: Any, toEntityId: ItemContainerEntityId): Future[Any] = {
    itemContainerRegion ? ForIdentifier(toEntityId, InternalCmdWrapper(cmd))
  }

  val receiveCmdAfterSuccessfulStorageCleanup: Receive = {
    case am: ActorMessage  => unhandledMsg(am, ItemContainerDeleted)
  }
}

case class ItemDetail(status: Int, detail: Option[String], isFromMigration: Boolean, createdAt: ZonedDateTime) {
  def isSame(id: ItemDetail): Boolean = {
    id.status == status && id.detail == detail
  }
}
case class ItemDetailResponse(id: ItemId, status: Int, isFromMigration: Boolean, detail: Option[String]) extends ActorMessage
case class MigrationCheckResult(checkedAt: ZonedDateTime,
                                migrateToLatestVersionedContainers: Boolean=false,
                                migrateToNextLinkedContainer: Boolean=false,
                                keepProcessingStartedMigrations: Boolean=false,
                                detail: Option[String]=None)
case class MigrationStatus(startedAt: Option[ZonedDateTime], finishedAt: Option[ZonedDateTime])
case class MigratedItemDetail(id: ItemId, toContainerEntityId: ItemContainerEntityId)

case class ItemNotFound(id: ItemId) extends ActorMessage

case class ItemContainerConfig(managerEntityId: ItemManagerEntityId,
                               migrateItemsToNextLinkedContainer: Boolean) extends ActorMessage

case class ScheduledJobDetail(isScheduled: Boolean, migrationCheckResultHistory: List[MigrationCheckResult])

case class ItemContainerState(itemContainerConfig: Option[ItemContainerConfig] = None,
                              itemContainerLink: Option[ItemContainerLink] = None,
                              items: Map[ItemId, ItemDetail],
                              migratedItems: Set[MigratedItemDetail],
                              migrationStatus: Map[ItemContainerEntityId, MigrationStatus],
                              migratedContainers: Map[ItemContainerEntityId, MigratedContainer],
                              scheduledJobDetail: ScheduledJobDetail) extends ActorMessage


case object CheckForPeriodicTaskExecution extends ActorMessage
case class ExecuteAndForwardReq(containerSequenceId: Int, cmd: Any) extends ActorMessage
case class ExecuteAndForwardResp(containerSequenceId: Int, respFromContainerEntityId: ItemContainerEntityId, resp: Any) extends ActorMessage

case object GetContainerStatus extends ActorMessage

/**
 *
 * @param id container entity id
 * @param items Map[ItemStatus, ItemCount]
 */
case class ContainerStatus(id: ItemContainerEntityId, items: Map[String, Int]) extends ActorMessage
case class ContainerItems(items: Map[ItemId, ItemDetail]) extends ActorMessage
case class MigratedContainer(time: ZonedDateTime, isStorageCleaned: Boolean) extends ActorMessage

case class UpdatePrevContainerId(id: ItemContainerEntityId) extends ActorMessage
case class UpdateNextContainerId(id: ItemContainerEntityId) extends ActorMessage
case class CleanStorage(requestedByItemContainerId: ItemContainerEntityId) extends ActorMessage
