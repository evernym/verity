package com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.LoggingReceive
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.persistence.SingletonChildrenPersistentActor
import com.evernym.verity.actor.resourceusagethrottling.blocking.ResourceBlockingStatusMngrCommon
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil._
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.actor.resourceusagethrottling.{EntityId, ResourceName}
import com.evernym.verity.config.{AppConfig, ConfigConstants}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.util.Util.{getActorRefFromSelection, strToBoolean}

import scala.concurrent.ExecutionContext
import java.time.ZonedDateTime


class ResourceBlockingStatusMngr(val aac: AgentActorContext, executionContext: ExecutionContext)
  extends SingletonChildrenPersistentActor
    with ResourceBlockingStatusMngrCommon
    with ResourceUsageCommon{

  override def futureExecutionContext: ExecutionContext = executionContext

  override val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case bu: BlockCaller                => handleBlockCaller(bu)
    case bur: BlockResourceForCaller    => handleBlockResourceForCaller(bur)
    case ubu: UnblockCaller              => handleUnblockCaller(ubu)
    case ubur: UnblockResourceForCaller  => handleUnblockResourceForCaller(ubur)
    case gbl: GetBlockedList            => sendBlockedList(gbl)
    case Done                           => // do nothing
  }

  override val receiveEvent: Receive = {
    case e: Any => processEvent(e)
  }

  def handleBlockCaller(bu: BlockCaller): Unit = {
    logger.debug("received block caller request: " + bu)

    val curDate = getCurrentUTCZonedDateTime
    val blockFrom = getMillisFromZonedDateTime(bu.blockFrom.getOrElse(curDate))

    // If period is 0 and allResources is Y/y then, at first, clear blocks on all blocked resources for this source ID
    // (and reset usage counters for these resources)
    if (bu.blockPeriod.contains(0) && bu.allBlockedResources.map(_.toUpperCase).contains(YES)) {
      entityBlockingStatus.get(bu.entityId).foreach { ebs =>
        ebs.resourcesStatus.filter(_._2.isBlocked(curDate)).foreach { case (rn, _) =>
          resetResourceUsageCounts(bu.entityId, rn)

          val resourceEvent = CallerResourceBlocked(bu.entityId, rn, blockFrom, 0)
          writeAndApply(resourceEvent)
          singletonParentProxyActor ! SendCmdToAllNodes(resourceEvent)
        }
      }
    }

    // If period is not 0 then, at first, clear unblocks on all unblocked resources for this source ID
    if (! bu.blockPeriod.contains(0)) {
      entityBlockingStatus.get(bu.entityId).foreach { ebs =>
        ebs.resourcesStatus.filter(_._2.isUnblocked(curDate)).foreach { case (rn, _) =>
          val resourceEvent = CallerResourceUnblocked(bu.entityId, rn, blockFrom, 0)
          writeAndApply(resourceEvent)
          singletonParentProxyActor ! SendCmdToAllNodes(resourceEvent)
        }
      }
    }

    val event = CallerBlocked(bu.entityId, blockFrom, getTimePeriodInSeconds(bu.blockPeriod))
    writeApplyAndSendItBack(event)
    singletonParentProxyActor ! SendCmdToAllNodes(event)
  }

  def handleBlockResourceForCaller(bur: BlockResourceForCaller): Unit = {
    logger.debug("received block caller resource request: " + bur)

    val ubs = entityBlockingStatus.getOrElse(bur.entityId, EntityBlockingStatus(buildEmptyBlockingDetail, Map.empty))
    val blockFrom = bur.blockFrom.getOrElse(getCurrentUTCZonedDateTime)

    if (ubs.status.isUnblocked(blockFrom)) {
      throw new BadRequestErrorException(
        BAD_REQUEST.statusCode, Option("Resource cannot be blocked for entity because entity is unblocked"))
    }

    // If period is 0 then, at first, reset this resource usage counters for this source ID
    if (bur.blockPeriod.contains(0)) {
      resetResourceUsageCounts(bur.entityId, bur.resourceName)
    }

    val event = CallerResourceBlocked(
      bur.entityId,
      bur.resourceName,
      getMillisFromZonedDateTime(blockFrom),
      getTimePeriodInSeconds(bur.blockPeriod)
    )
    writeApplyAndSendItBack(event)
    singletonParentProxyActor ! SendCmdToAllNodes(event)
  }

  def handleUnblockCaller(ubu: UnblockCaller): Unit = {
    logger.debug("received unblock caller request: " + ubu)

    val curDate = getCurrentUTCZonedDateTime
    val unblockFrom = getMillisFromZonedDateTime(ubu.unblockFrom.getOrElse(curDate))

    // At first, clear blocks on all blocked resources for this source ID (but preserve usage counters)
    entityBlockingStatus.get(ubu.entityId).foreach { ebs =>
      ebs.resourcesStatus.filter(_._2.isBlocked(curDate)).foreach { case (rn, _) =>
        val resourceEvent = CallerResourceBlocked(ubu.entityId, rn, unblockFrom, 0)
        writeAndApply(resourceEvent)
        singletonParentProxyActor ! SendCmdToAllNodes(resourceEvent)
      }
    }

    val event = CallerUnblocked(ubu.entityId, unblockFrom, getTimePeriodInSeconds(ubu.unblockPeriod))
    writeApplyAndSendItBack(event)
    singletonParentProxyActor ! SendCmdToAllNodes(event)
  }

  def handleUnblockResourceForCaller(ubur: UnblockResourceForCaller): Unit = {
    logger.debug("received unblock caller resource request: " + ubur)

    val ubs = entityBlockingStatus.getOrElse(ubur.entityId, EntityBlockingStatus(buildEmptyBlockingDetail, Map.empty))
    val unblockFrom = ubur.unblockFrom.getOrElse(getCurrentUTCZonedDateTime)

    if (ubs.status.isBlocked(unblockFrom)) {
      throw new BadRequestErrorException(
        BAD_REQUEST.statusCode, Option("Resource cannot be unblocked for entity because entity is blocked"))
    }

    val event = CallerResourceUnblocked(
      ubur.entityId,
      ubur.resourceName,
      getMillisFromZonedDateTime(unblockFrom),
      getTimePeriodInSeconds(ubur.unblockPeriod)
    )
    writeApplyAndSendItBack(event)
    singletonParentProxyActor ! SendCmdToAllNodes(event)
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
        sender() ! UsageBlockingStatusChunk(chunk, ind + 1, groupedList.size)
      }
    } else {
      sender() ! UsageBlockingStatusChunk(Map.empty, 1, 1)
    }
  }

  def system: ActorSystem = aac.system
  override def appConfig: AppConfig = aac.appConfig
  def agentActorContext: AgentActorContext = aac
  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)
  override lazy val persistenceEncryptionKey: String = appConfig.getStringReq(ConfigConstants.SECRET_RESOURCE_BLOCKING_STATUS_MNGR)

}

trait UpdateBlockingStatus extends ActorMessage

case class GetBlockedList(onlyBlocked: Boolean, onlyUnblocked: Boolean, onlyActive: Boolean,
                          inChunks: Boolean, ids: Option[String]=None,
                          resourceNames: Option[String]=None) extends ActorMessage

object GetBlockedList extends ActorMessage {
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

  private def isInBlockingPeriod(cdt: ZonedDateTime): Boolean =
    blockFrom.exists(_.isBefore(cdt)) && blockTill.forall(_.isAfter(cdt))

  private def isInUnblockingPeriod(cdt: ZonedDateTime): Boolean =
    unblockFrom.exists(_.isBefore(cdt)) && unblockTill.forall(_.isAfter(cdt))

  def isNeutral(cdt: ZonedDateTime): Boolean =
    !isInBlockingPeriod(cdt) && !isInUnblockingPeriod(cdt)

  def isBlocked(cdt: ZonedDateTime): Boolean =
    isInBlockingPeriod(cdt) && !isInUnblockingPeriod(cdt)

  def isUnblocked(cdt: ZonedDateTime): Boolean =
    isInUnblockingPeriod(cdt)
}

/**
 * entity's blocking (block & unblock both) status
 * @param status blocking status of the entity itself
 * @param resourcesStatus blocking status for different resources (endpoint/messages) for the entity
 */
case class EntityBlockingStatus(status: BlockingDetail, resourcesStatus: Map[ResourceName, BlockingDetail])
  extends ActorMessage

case class BlockCaller(entityId: EntityId,
                       blockFrom: Option[ZonedDateTime]=None,
                       blockPeriod: Option[Long]=None,
                       allBlockedResources: Option[String]=None) extends ActorMessage

case class BlockResourceForCaller(entityId: EntityId,
                                  resourceName: ResourceName,
                                  blockFrom: Option[ZonedDateTime]=None,
                                  blockPeriod: Option[Long]=None) extends ActorMessage

case class UnblockCaller(entityId: EntityId,
                         unblockFrom: Option[ZonedDateTime]=None,
                         unblockPeriod: Option[Long]=None) extends ActorMessage

case class UnblockResourceForCaller(entityId: EntityId,
                                    resourceName: ResourceName,
                                    unblockFrom: Option[ZonedDateTime]=None,
                                    unblockPeriod: Option[Long]=None) extends ActorMessage

/**
 * used to send blocking status from cluster singleton to each node
 * @param usageBlockingStatus chunked blocking status
 * @param currentChunkNumber current chunk number
 * @param totalChunks total chunks
 */
case class UsageBlockingStatusChunk(usageBlockingStatus: Map[EntityId, EntityBlockingStatus],
                                    currentChunkNumber: Int, totalChunks: Int) extends ActorMessage


object ResourceBlockingStatusMngr {
  val name: String = RESOURCE_BLOCKING_STATUS_MNGR
  def props(agentActorContext: AgentActorContext, executionContext: ExecutionContext): Props =
    Props(new ResourceBlockingStatusMngr(agentActorContext, executionContext))
}
