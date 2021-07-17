package com.evernym.verity.actor.resourceusagethrottling.blocking

import java.time.ZonedDateTime

import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.{BlockingDetail, EntityBlockingStatus}
import com.evernym.verity.actor.resourceusagethrottling.EntityId
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil.{getZonedDateTimeFromPeriod, _}
import com.evernym.verity.actor.{CallerBlocked, CallerResourceBlocked, CallerResourceUnblocked, CallerUnblocked}
import com.evernym.verity.util.TimeZoneUtil.{getCurrentUTCZonedDateTime, getZonedDateTimeFromMillis}

trait ResourceBlockingStatusMngrCommon {

  /**
   * mapping between entity id and its blocking status (block & unblock both)
   */
  protected var entityBlockingStatus = Map.empty[EntityId, EntityBlockingStatus]

  val processEvent: PartialFunction[Any, Unit] = {
    case cb: CallerBlocked =>
      val ubs = entityBlockingStatus.getOrElse(cb.callerId, getEmptyEntityBlockingStatus)
      val newUbs = ubs.copy(status = updateBlockingDetail(cb.blockFrom, cb.blockPeriod, ubs.status))
      entityBlockingStatus = entityBlockingStatus + (cb.callerId -> newUbs)

    case crb: CallerResourceBlocked =>
      val ubs = entityBlockingStatus.getOrElse(crb.callerId, EntityBlockingStatus(buildEmptyBlockingDetail, Map.empty))
      val urbd = ubs.resourcesStatus.getOrElse(crb.resourceName, buildEmptyBlockingDetail)
      val newUrbs = ubs.resourcesStatus + (crb.resourceName -> updateBlockingDetail(crb.blockFrom, crb.blockPeriod, urbd))
      val newUbs = ubs.copy(resourcesStatus = newUrbs)
      entityBlockingStatus = entityBlockingStatus + (crb.callerId -> newUbs)

    case cub: CallerUnblocked =>
      val ubs = entityBlockingStatus.getOrElse(cub.callerId, getEmptyEntityBlockingStatus)
      val newUbs = ubs.copy(status = updateUnblockingDetail(cub.unblockFrom, cub.unblockPeriod, ubs.status))
      entityBlockingStatus = entityBlockingStatus + (cub.callerId -> newUbs)

    case crub: CallerResourceUnblocked =>
      val ubs = entityBlockingStatus.getOrElse(crub.callerId, EntityBlockingStatus(buildEmptyBlockingDetail, Map.empty))
      val urbd = ubs.resourcesStatus.getOrElse(crub.resourceName, buildEmptyBlockingDetail)
      val newUrbs = ubs.resourcesStatus + (crub.resourceName -> updateUnblockingDetail(crub.unblockFrom, crub.unblockPeriod, urbd))
      val newUbs = ubs.copy(resourcesStatus = newUrbs)
      entityBlockingStatus = entityBlockingStatus + (crub.callerId -> newUbs)
  }

  /**
   * returns filtered entities who has 'blocked' or 'unblocked' resources
   * @param bl blocking list (blocked & unblocked)
   * @param curDateTimeOpt optional, Some(date) if active list is required else None
   * @return
   */
  def filterAllResources(bl: Map[EntityId, EntityBlockingStatus], curDateTimeOpt: Option[ZonedDateTime] = None):
  Map[EntityId, EntityBlockingStatus] = {

    //first filter all active resources
    val filteredActiveRsrc = bl.map { case (entityId, blockingStatus) =>
      val activeResources = blockingStatus.resourcesStatus.filter { case (_, bd) =>
        curDateTimeOpt.forall { cdt =>
          bd.isBlocked(cdt) || bd.isUnblocked(cdt)
        }
      }
      entityId -> blockingStatus.copy(resourcesStatus = activeResources)
    }

    // now filter all active entities (which are either blocked/unblocked or has NON empty active resources)
    filteredActiveRsrc.filter { case (_, ebs) =>
      curDateTimeOpt.forall { cdt =>
        ebs.status.isBlocked(cdt) ||
          ebs.status.isUnblocked(cdt) ||
          ebs.resourcesStatus.nonEmpty
      }
    }
  }

  /**
   * returns filtered entities who has 'blocked' resources
   * @param bl blocking list (blocked and unblocked)
   * @param curDateTimeOpt optional, Some(date) if active list is required else None
   * @return
   */
  def filterBlockedUserResources(bl: Map[EntityId, EntityBlockingStatus], curDateTimeOpt: Option[ZonedDateTime] = None):
  Map[EntityId, EntityBlockingStatus] = {
    val filteredByBlockedRsrc = bl.map { case (entityId, blockingStatus) =>
      val filtered = blockingStatus.resourcesStatus.filter { case (_, bd) =>
        curDateTimeOpt.map { cdt =>
          bd.isBlocked(cdt)
        }.getOrElse {
          bd.blockFrom.isDefined
        }
      }
      entityId -> blockingStatus.copy(resourcesStatus = filtered)
    }
    filteredByBlockedRsrc.filter { case (_, ur) =>
      curDateTimeOpt.map { cdt =>
        ur.status.isBlocked(cdt)
      }.getOrElse {
        ur.status.blockFrom.isDefined
      } || ur.resourcesStatus.nonEmpty
    }
  }

  /**
   * returns filtered entities who has 'unblocked' resources
   * @param bl blocking list (blocked and unblocked)
   * @param curDateTimeOpt optional, Some(date) if active list is required else None
   * @return
   */
  def filterUnblockedUserResources(bl: Map[EntityId, EntityBlockingStatus], curDateTimeOpt: Option[ZonedDateTime]):
  Map[EntityId, EntityBlockingStatus] = {
    val filteredByUnblockedRsrc = bl.map { urb =>
      val filtered = urb._2.resourcesStatus.filter { case (_, bd) =>
        curDateTimeOpt.map { cdt =>
          bd.isUnblocked(cdt)
        }.getOrElse {
          bd.unblockFrom.isDefined
        }
      }
      urb._1 -> urb._2.copy(resourcesStatus = filtered)
    }
    filteredByUnblockedRsrc.filter { case (_, ur) =>
      curDateTimeOpt.map { cdt =>
        ur.status.isUnblocked(cdt)
      }.getOrElse {
        ur.status.unblockFrom.isDefined
      } || ur.resourcesStatus.nonEmpty
    }
  }

  def getOnlyBlocked(onlyActive: Boolean=true):  Map[EntityId, EntityBlockingStatus] = {
    val curDateTime = if (onlyActive) Option(getCurrentUTCZonedDateTime) else None
    filterBlockedUserResources(entityBlockingStatus, curDateTime)
  }

  def getOnlyUnblocked(onlyActive: Boolean=true):  Map[EntityId, EntityBlockingStatus] = {
    val curDateTime = if (onlyActive) Option(getCurrentUTCZonedDateTime) else None
    filterUnblockedUserResources(entityBlockingStatus, curDateTime)
  }

  def getAllActive(onlyActive: Boolean=true):  Map[EntityId, EntityBlockingStatus] = {
    val curDateTime = if (onlyActive) Option(getCurrentUTCZonedDateTime) else None
    filterAllResources(entityBlockingStatus, curDateTime)
  }

  def updateBlockingDetail(blockFromTimeInMillis: Long, blockPeriodInSeconds: Long, toBlockingDetail: BlockingDetail): BlockingDetail = {
    val blockedFromDateTime = getZonedDateTimeFromMillis(blockFromTimeInMillis)
    val blockTillDateTime = getZonedDateTimeFromPeriod(blockedFromDateTime, blockPeriodInSeconds)
    toBlockingDetail.copy(
      blockFrom = Option(blockedFromDateTime),
      blockTill = blockTillDateTime,
      unblockFrom = None,
      unblockTill = None
    )
  }

  def updateUnblockingDetail(unblockFromTimeInMillis: Long, unblockPeriodInSeconds: Long, toBlockingDetail: BlockingDetail): BlockingDetail = {
    val unblockFromDateTime = getZonedDateTimeFromMillis(unblockFromTimeInMillis)
    val unblockTillDateTime = getZonedDateTimeFromPeriod(unblockFromDateTime, unblockPeriodInSeconds)
    toBlockingDetail.copy(
      unblockFrom = Option(unblockFromDateTime),
      unblockTill = unblockTillDateTime,
      blockFrom = None,
      blockTill = None)
  }

  def buildEmptyBlockingDetail: BlockingDetail =  BlockingDetail(None, None, None, None)

  def getEmptyEntityBlockingStatus: EntityBlockingStatus = EntityBlockingStatus(buildEmptyBlockingDetail, Map.empty)

}
