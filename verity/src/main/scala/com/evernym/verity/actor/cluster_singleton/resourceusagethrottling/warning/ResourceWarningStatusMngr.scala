package com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning

import java.time.ZonedDateTime

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.LoggingReceive
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.persistence.SingletonChildrenPersistentActor
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil._
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.actor.resourceusagethrottling.warning.ResourceWarningStatusMngrCommon
import com.evernym.verity.actor.resourceusagethrottling.{EntityId, ResourceName}
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.util.Util.{getActorRefFromSelection, strToBoolean}

class ResourceWarningStatusMngr(val aac: AgentActorContext)
  extends SingletonChildrenPersistentActor
    with ResourceWarningStatusMngrCommon
    with ResourceUsageCommon {

  override val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case wu: WarnCaller               => handleWarnCaller(wu)
    case wur: WarnResourceForCaller   => handleWarnResourceForCaller(wur)
    case uu: UnwarnCaller             => handleUnwarnCaller(uu)
    case uur: UnwarnResourceForCaller => handleUnwarnCallerForResource(uur)
    case gwl: GetWarnedList           => sendWarnedList(gwl)
    case Done                         => // do nothing
  }

  override val receiveEvent: Receive = {
    case e: Any => processEvent(e)
  }

  def handleWarnCaller(wu: WarnCaller): Unit = {
    logger.debug("received warn caller request: " + wu)
    val warnFrom = getMillisFromZonedDateTime(wu.warnFrom.getOrElse(getCurrentUTCZonedDateTime))
    // First set period to 0 (clear warning) on all resources with the same ID if allWarnedResources is set to Y/y
    if (wu.warnPeriod.getOrElse(None) == 0
      && wu.allWarnedResources.map(_.toUpperCase).contains(YES)) {
      val curDate = getCurrentUTCZonedDateTime
      logger.debug(s"Removed ${wu.entityId} caller warning")
      resourceWarningStatus.get(wu.entityId).foreach { rubs =>
        rubs.resourcesStatus.filter(_._2.isWarned(curDate)).foreach { case (rn, _) =>
          removeResourceWarning(wu.entityId, rn, warnFrom)
          resetResourceUsageCounts(wu.entityId, rn)
        }
      }
    }
    val event = CallerWarned(wu.entityId, warnFrom, getTimePeriodInSeconds(wu.warnPeriod))
    writeApplyAndSendItBack(event)
    singletonParentProxyActor ! SendCmdToAllNodes(event)
  }

  def handleWarnResourceForCaller(wur: WarnResourceForCaller): Unit = {
    logger.debug("received warn caller resource request: " + wur)
    val warnFrom = getMillisFromZonedDateTime(wur.warnFrom.getOrElse(getCurrentUTCZonedDateTime))
    val event = CallerResourceWarned(wur.entityId, wur.resourceName, warnFrom, getTimePeriodInSeconds(wur.warnPeriod))
    writeApplyAndSendItBack(event)
    singletonParentProxyActor ! SendCmdToAllNodes(event)

    // Set period to 0 (clear warning) on all resources with the same ID if allWarnedResources is set to 'Y' or 'y'
    if (wur.warnPeriod.getOrElse(None) == 0) {
      removeResourceWarning(wur.entityId, wur.resourceName, warnFrom)
      resetResourceUsageCounts(wur.entityId, wur.resourceName)
    }
  }

  def handleUnwarnCaller(uu: UnwarnCaller): Unit = {
    logger.debug("received unwarn caller request: " + uu)
    val curDate = getCurrentUTCZonedDateTime
    val unwarnFrom = getMillisFromZonedDateTime(uu.unwarnFrom.getOrElse(curDate))
    val event = CallerUnwarned(uu.entityId, unwarnFrom, getTimePeriodInSeconds(uu.unwarnPeriod))
    writeApplyAndSendItBack(event)
    singletonParentProxyActor ! SendCmdToAllNodes(event)

    if (uu.allWarnedResources.map(_.toUpperCase).contains(YES)) {
      resourceWarningStatus.get(uu.entityId).foreach { ruws =>
        ruws.resourcesStatus.filter(_._2.isWarned(curDate)).foreach { case (rn, _) =>
          val resourceEvent = CallerResourceUnwarned(uu.entityId, rn, unwarnFrom, getTimePeriodInSeconds(uu.unwarnPeriod))
          writeAndApply(resourceEvent)
          singletonParentProxyActor ! SendCmdToAllNodes(resourceEvent)
        }
      }
    }
  }

  def handleUnwarnCallerForResource(uur: UnwarnResourceForCaller): Unit = {
    logger.debug("received unwarn caller resource request: " + uur)
    val unwarnFrom = getMillisFromZonedDateTime(uur.unwarnFrom.getOrElse(getCurrentUTCZonedDateTime))
    val event = CallerResourceUnwarned(uur.entityId, uur.resourceName, unwarnFrom, getTimePeriodInSeconds(uur.unwarnPeriod))
    writeApplyAndSendItBack(event)
    singletonParentProxyActor ! SendCmdToAllNodes(event)
  }

  def prepareValidListOfStringsFromCsv(csvStr: Option[String]): List[String] =
    csvStr.map(_.split(",").map(_.trim).toList).getOrElse(List.empty).filter(_.nonEmpty)

  def sendWarnedList(gwl: GetWarnedList): Unit = {
    val filterByIpAddresses = prepareValidListOfStringsFromCsv(gwl.ids)
    val filterByResources = prepareValidListOfStringsFromCsv(gwl.resourceNames)

    val validWarningList =
      if (gwl.onlyWarned) getOnlyWarned(gwl.onlyActive)
      else if (gwl.onlyUnwarned) getOnlyUnwarned(gwl.onlyActive)
      else getAll(gwl.onlyActive)

    val filteredByIpAddresses =
      if (filterByIpAddresses.isEmpty) validWarningList
      else validWarningList.filter(rec => filterByIpAddresses.contains(rec._1))

    val filteredByResources =
      if (filterByResources.isEmpty) filteredByIpAddresses
      else filteredByIpAddresses.map { rec =>
        val filterByRes = rec._2.resourcesStatus.filter(res => filterByResources.contains(res._1))
        rec._1 ->  rec._2.copy(resourcesStatus = filterByRes)
      }

    val groupedList = filteredByResources.grouped(100)
    if (groupedList.nonEmpty) {
      groupedList.zipWithIndex.foreach { case (chunk, ind) =>
        sender ! UsageWarningStatusChunk(chunk, ind + 1, groupedList.size)
      }
    } else {
      sender ! UsageWarningStatusChunk(Map.empty, 1, 1)
    }
  }

  def removeResourceWarning(entityId: EntityId, resourceName: ResourceName, warnFrom: Long): Unit = {
    logger.debug(s"Remove $resourceName resource warnings")
    val event = CallerResourceWarned(entityId, resourceName, warnFrom, getTimePeriodInSeconds(Some(0)))
    writeApplyAndSendItBack(event)
    singletonParentProxyActor ! SendCmdToAllNodes(event)
  }

  def system: ActorSystem = aac.system
  override def appConfig: AppConfig = aac.appConfig
  def agentActorContext: AgentActorContext = aac
  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)
  override lazy val persistenceEncryptionKey: String = appConfig.getConfigStringReq(CommonConfig.SECRET_RESOURCE_WARNING_STATUS_MNGR)
}

trait UpdateWarningStatus extends ActorMessage

case class GetWarnedList(onlyWarned: Boolean, onlyUnwarned: Boolean, onlyActive: Boolean,
                         inChunks: Boolean, ids: Option[String]=None, resourceNames: Option[String]=None) extends ActorMessage

object GetWarnedList extends ActorMessage {
  def apply(onlyWarned: String, onlyUnwarned: String, onlyActive: String, inChunks: Boolean,
            ids: Option[String], resourceNames: Option[String]): GetWarnedList = {
    val gwl = GetWarnedList(strToBoolean(onlyWarned), strToBoolean(onlyUnwarned),
      strToBoolean(onlyActive), inChunks, ids, resourceNames)
    if (gwl.onlyWarned && gwl.onlyUnwarned) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option( "'onlyWarned' and 'onlyUnwarned' both can't be Y"))
    }
    gwl
  }

}

/**
 * warning detail
 * @param warnFrom warning start time
 * @param warnTill warning finish time (if None, it would be indefinite warning)
 * @param unwarnFrom unwarn start time
 * @param unwarnTill unwarn finish time (if None, it would be indefinite unwarned)
 */
case class WarningDetail(warnFrom: Option[ZonedDateTime], warnTill: Option[ZonedDateTime],
                         unwarnFrom: Option[ZonedDateTime], unwarnTill: Option[ZonedDateTime]) {

  def isInWarningPeriod(cdt: ZonedDateTime): Boolean =
    warnFrom.exists(_.isBefore(cdt)) && warnTill.forall(_.isAfter(cdt))

  def isInUnwarningPeriod(cdt: ZonedDateTime): Boolean =
    unwarnFrom.exists(_.isBefore(cdt)) && unwarnTill.forall(_.isAfter(cdt))

  def isWarned(cdt: ZonedDateTime): Boolean =
    isInWarningPeriod(cdt) && ! isInUnwarningPeriod(cdt)

}

/**
 * entity's warning (warn & unwarn both) status
 * @param status warning status of the entity itself
 * @param resourcesStatus warning status for different resources (endpoint/messages) for the entity
 */
case class EntityWarningStatus(status: WarningDetail, resourcesStatus: Map[ResourceName, WarningDetail]) extends ActorMessage

case class WarnCaller(entityId: EntityId,
                      warnFrom: Option[ZonedDateTime]=None,
                      warnPeriod: Option[Long]=None,
                      allWarnedResources: Option[String]=None) extends ActorMessage

case class WarnResourceForCaller(entityId: EntityId,
                                 resourceName: ResourceName,
                                 warnFrom: Option[ZonedDateTime]=None,
                                 warnPeriod: Option[Long]=None) extends ActorMessage

case class UnwarnCaller(entityId: EntityId,
                        unwarnFrom: Option[ZonedDateTime]=None,
                        unwarnPeriod: Option[Long]=None,
                        allWarnedResources: Option[String]=None) extends ActorMessage

case class UnwarnResourceForCaller(entityId: EntityId,
                                   resourceName: ResourceName,
                                   unwarnFrom: Option[ZonedDateTime]=None,
                                   unwarnPeriod: Option[Long]=None) extends ActorMessage

/**
 * used to send warning status from cluster singleton to each node
 * @param usageWarningStatus chunked warning status
 * @param currentChunkNumber current chunk number
 * @param totalChunks total chunks
 */
case class UsageWarningStatusChunk(usageWarningStatus: Map[EntityId, EntityWarningStatus],
                                   currentChunkNumber: Int, totalChunks: Int) extends ActorMessage


object ResourceWarningStatusMngr {
  val name: String = RESOURCE_WARNING_STATUS_MNGR
  def props(agentActorContext: AgentActorContext): Props =
    Props(new ResourceWarningStatusMngr(agentActorContext))
}
