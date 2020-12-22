package com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking

import java.time.ZonedDateTime

import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.persistence.SingletonChildrenPersistentActor
import com.evernym.verity.actor.resourceusagethrottling.blocking.ResourceBlockingStatusMngrCommon
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil._
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.actor.resourceusagethrottling.{EntityId, ResourceName}
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.util.Util.{getActorRefFromSelection, strToBoolean}


class ResourceBlockingStatusMngr(val aac: AgentActorContext)
  extends SingletonChildrenPersistentActor
    with ResourceBlockingStatusMngrCommon
    with ResourceUsageCommon{

  override val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case bu: BlockCaller                => handleBlockCaller(bu)
    case bur: BlockResourceForCaller    => handleBlockResourceForCaller(bur)
    case uu: UnblockCaller              => handleUnblockCaller(uu)
    case uur: UnblockResourceForCaller  => handleUnblockForCaller(uur)
    case gbl: GetBlockedList            => sendBlockedList(gbl)
    case Done                           => // do nothing
  }

  override val receiveEvent: Receive = {
    case e: Any => processEvent(e)
  }

  def handleBlockCaller(bu: BlockCaller): Unit = {
    logger.debug("received block caller request: " + bu)
    val blockFromTimeInMillis = getMillisFromZonedDateTime(bu.blockFrom.getOrElse(getCurrentUTCZonedDateTime))

    // First set period to 0 (clear block) on all resources with the same ID if allBlockedResources is set to Y/y
    if (bu.blockPeriod.getOrElse(None) == 0
      && bu.allBlockedResources.map(_.toUpperCase).contains(YES)) {
      val curDate = getCurrentUTCZonedDateTime
      logger.debug(s"Removed ${bu.entityId} caller block")
      entityBlockingStatus.get(bu.entityId).foreach { ebs =>
        ebs.resourcesStatus.filter(_._2.isBlocked(curDate)).foreach { case (rn, _) =>
          removeResourceBlock(bu.entityId, rn, blockFromTimeInMillis)
          resetResourceUsageCounts(bu.entityId, rn)
        }
      }
    }

    val event = CallerBlocked(bu.entityId, blockFromTimeInMillis, getTimePeriodInSeconds(bu.blockPeriod))
    writeApplyAndSendItBack(event)
    sendChangeToNodeSingleton(event)
  }

  def handleBlockResourceForCaller(bur: BlockResourceForCaller): Unit = {
    logger.debug("received block caller resource request: " + bur)
    val blockFromTimeInMillis = getMillisFromZonedDateTime(bur.blockFrom.getOrElse(getCurrentUTCZonedDateTime))
    val event = CallerResourceBlocked(bur.entityId, bur.resourceName, blockFromTimeInMillis, getTimePeriodInSeconds(bur.blockPeriod))
    writeApplyAndSendItBack(event)
    sendChangeToNodeSingleton(event)

    // Set period to 0 (clear block) on all resources with the same ID if allBlockedResources is set to 'Y' or 'y'
    if (bur.blockPeriod.getOrElse(None) == 0) {
      removeResourceBlock(bur.entityId, bur.resourceName, blockFromTimeInMillis)
      resetResourceUsageCounts(bur.entityId, bur.resourceName)
    }
  }

  def handleUnblockCaller(uu: UnblockCaller): Unit = {
    logger.debug("received unblock caller request: " + uu)
    val curDate = getCurrentUTCZonedDateTime
    val unblockFromTimeInMillis = getMillisFromZonedDateTime(uu.unblockFrom.getOrElse(curDate))
    val event = CallerUnblocked(uu.entityId, unblockFromTimeInMillis, getTimePeriodInSeconds(uu.unblockPeriod))
    writeApplyAndSendItBack(event)
    sendChangeToNodeSingleton(event)

    if (uu.allBlockedResources.map(_.toUpperCase).contains(YES)) {
      entityBlockingStatus.get(uu.entityId).foreach { rubs =>
        rubs.resourcesStatus.filter(_._2.isBlocked(curDate)).foreach { case (rn, _) =>
          val resourceEvent = CallerResourceUnblocked(uu.entityId, rn, unblockFromTimeInMillis, getTimePeriodInSeconds(uu.unblockPeriod))
          writeAndApply(resourceEvent)
          sendChangeToNodeSingleton(resourceEvent)
        }
      }
    }
  }

  def handleUnblockForCaller(uur: UnblockResourceForCaller): Unit = {
    logger.debug("received unblock caller resource request: " + uur)
    val unblockFrom = getMillisFromZonedDateTime(uur.unblockFrom.getOrElse(getCurrentUTCZonedDateTime))
    val event = CallerResourceUnblocked(uur.entityId, uur.resourceName, unblockFrom, getTimePeriodInSeconds(uur.unblockPeriod))
    writeApplyAndSendItBack(event)
    sendChangeToNodeSingleton(event)
  }

  def prepareValidListOfStringsFromCsv(csvStr: Option[String]): List[String] =
    csvStr.map(_.split(",").map(_.trim).toList).getOrElse(List.empty).filter(_.nonEmpty)

  def sendBlockedList(gbl: GetBlockedList): Unit = {
    val filterByIpAddresses = prepareValidListOfStringsFromCsv(gbl.ids)
    val filterByResources = prepareValidListOfStringsFromCsv(gbl.resourceNames)

    val validBlockingList =
      if (gbl.onlyBlocked) getOnlyBlocked(gbl.onlyActive)
      else if (gbl.onlyUnblocked) getOnlyUnblocked(gbl.onlyActive)
      else getAllActive(gbl.onlyActive)

    val filteredByIpAddresses =
      if (filterByIpAddresses.isEmpty) validBlockingList
      else validBlockingList.filter(rec => filterByIpAddresses.contains(rec._1))

    val filteredByResources =
      if (filterByResources.isEmpty) filteredByIpAddresses
      else filteredByIpAddresses.map { rec =>
        val filterByRes = rec._2.resourcesStatus.filter(res => filterByResources.contains(res._1))
        rec._1 ->  rec._2.copy(resourcesStatus = filterByRes)
      }

    val groupedList = filteredByResources.grouped(100)
    if (groupedList.nonEmpty) {
      groupedList.zipWithIndex.foreach { case (chunk, ind) =>
        sender ! UsageBlockingStatusChunk(chunk, ind + 1, groupedList.size)
      }
    } else {
      sender ! UsageBlockingStatusChunk(Map.empty, 1, 1)
    }
  }

  def removeResourceBlock(entityId: EntityId, resourceName: ResourceName, blockFromTimeInMillis: Long): Unit = {
    logger.debug(s"Remove $resourceName resource blocks")
    val event = CallerResourceBlocked(entityId, resourceName, blockFromTimeInMillis, getTimePeriodInSeconds(Some(0)))
    writeApplyAndSendItBack(event)
    singletonParentProxyActor ! SendCmdToAllNodes(event)
  }

  def sendChangeToNodeSingleton(changeEvent: Any): Unit = {
    singletonParentProxyActor ! SendCmdToAllNodes(changeEvent)
  }

  override def appConfig: AppConfig = aac.appConfig
  def agentActorContext: AgentActorContext = aac
  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)
  override lazy val persistenceEncryptionKey: String = appConfig.getConfigStringReq(CommonConfig.SECRET_RESOURCE_BLOCKING_STATUS_MNGR)

}

trait UpdateBlockingStatus extends ActorMessageClass

case class GetBlockedList(onlyBlocked: Boolean, onlyUnblocked: Boolean, onlyActive: Boolean,
                          inChunks: Boolean, ids: Option[String]=None,
                          resourceNames: Option[String]=None) extends ActorMessageClass

object GetBlockedList extends ActorMessageObject {
  def apply(onlyBlocked: String, onlyUnblocked: String, onlyActive: String, inChunks: Boolean,
            ids: Option[String], resourceNames: Option[String]): GetBlockedList = {
    val gbl = GetBlockedList(strToBoolean(onlyBlocked), strToBoolean(onlyUnblocked),
      strToBoolean(onlyActive), inChunks, ids, resourceNames)
    if (gbl.onlyBlocked && gbl.onlyUnblocked) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option( "'onlyBlocked' and 'onlyUnblocked' both can't be Y"))
    }
    gbl
  }
}

/**
 * blocking detail
 * @param blockFrom blocking start time
 * @param blockTill blocking finish time (if None, it would be indefinite blocking)
 * @param unblockFrom unblock start time
 * @param unblockTill unblock finish time (if None, it would be indefinite unblocked)
 */
case class BlockingDetail(blockFrom: Option[ZonedDateTime], blockTill: Option[ZonedDateTime],
                          unblockFrom: Option[ZonedDateTime], unblockTill: Option[ZonedDateTime]) {

  def isInBlockingPeriod(cdt: ZonedDateTime): Boolean =
    blockFrom.exists(_.isBefore(cdt)) && blockTill.forall(_.isAfter(cdt))

  def isInUnblockingPeriod(cdt: ZonedDateTime): Boolean =
    unblockFrom.exists(_.isBefore(cdt)) && unblockTill.forall(_.isAfter(cdt))

  def isBlocked(cdt: ZonedDateTime): Boolean =
    isInBlockingPeriod(cdt) &&
      //this below line is only useful if we want to allow overlapping blocking/unblocking
      // (right now we don't allow overlapping blocking/unblocking, but below check won't hurt us anyway)
      ! isInUnblockingPeriod(cdt)
}

/**
 * entity's blocking (block & unblock both) status
 * @param status blocking status of the entity itself
 * @param resourcesStatus blocking status for different resources (endpoint/messages) for the entity
 */
case class EntityBlockingStatus(status: BlockingDetail, resourcesStatus: Map[ResourceName, BlockingDetail])
  extends ActorMessageClass

case class BlockCaller(entityId: EntityId,
                       blockFrom: Option[ZonedDateTime]=None,
                       blockPeriod: Option[Long]=None,
                       allBlockedResources: Option[String]=None) extends ActorMessageClass

case class BlockResourceForCaller(entityId: EntityId,
                                  resourceName: ResourceName,
                                  blockFrom: Option[ZonedDateTime]=None,
                                  blockPeriod: Option[Long]=None) extends ActorMessageClass

case class UnblockCaller(entityId: EntityId,
                         unblockFrom: Option[ZonedDateTime]=None,
                         unblockPeriod: Option[Long]=None,
                         allBlockedResources: Option[String]=None) extends ActorMessageClass

case class UnblockResourceForCaller(entityId: EntityId,
                                    resourceName: ResourceName,
                                    unblockFrom: Option[ZonedDateTime]=None,
                                    unblockPeriod: Option[Long]=None) extends ActorMessageClass

/**
 * used to send blocking status from cluster singleton to each node
 * @param usageBlockingStatus chunked blocking status
 * @param currentChunkNumber current chunk number
 * @param totalChunks total chunks
 */
case class UsageBlockingStatusChunk(usageBlockingStatus: Map[EntityId, EntityBlockingStatus],
                                    currentChunkNumber: Int, totalChunks: Int) extends ActorMessageClass


object ResourceBlockingStatusMngr {
  val name: String = RESOURCE_BLOCKING_STATUS_MNGR
  def props(agentActorContext: AgentActorContext): Props =
    Props(new ResourceBlockingStatusMngr(agentActorContext))
}
