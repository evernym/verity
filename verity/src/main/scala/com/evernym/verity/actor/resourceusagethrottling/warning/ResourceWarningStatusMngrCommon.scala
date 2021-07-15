package com.evernym.verity.actor.resourceusagethrottling.warning

import java.time.ZonedDateTime

import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.{EntityWarningStatus, WarningDetail}
import com.evernym.verity.actor.resourceusagethrottling.EntityId
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil.{getZonedDateTimeFromPeriod, _}
import com.evernym.verity.actor.{CallerResourceUnwarned, CallerResourceWarned, CallerUnwarned, CallerWarned}
import com.evernym.verity.util.TimeZoneUtil.{getCurrentUTCZonedDateTime, getZonedDateTimeFromMillis}

trait ResourceWarningStatusMngrCommon {
  /**
   * mapping between entity id and its warning status (warn & unwarn both)
   */
  protected var entityWarningStatus = Map.empty[EntityId, EntityWarningStatus]

  val processEvent: PartialFunction[Any, Unit] = {
    case cw: CallerWarned =>
      val uws = entityWarningStatus.getOrElse(cw.callerId, getEmptyEntityWarningStatus)
      val newUws = uws.copy(status = updateWarningDetail(cw.warnFrom, cw.warnPeriod, uws.status))
      entityWarningStatus = entityWarningStatus + (cw.callerId -> newUws)

    case crw: CallerResourceWarned =>
      val uws = entityWarningStatus.getOrElse(crw.callerId, EntityWarningStatus(buildEmptyWarningDetail, Map.empty))
      val urwd = uws.resourcesStatus.getOrElse(crw.resourceName, buildEmptyWarningDetail)
      val newUrws = uws.resourcesStatus + (crw.resourceName -> updateWarningDetail(crw.warnFrom, crw.warnPeriod, urwd))
      val newUws = uws.copy(resourcesStatus = newUrws)
      entityWarningStatus = entityWarningStatus + (crw.callerId -> newUws)

    case cuw: CallerUnwarned =>
      val uws = entityWarningStatus.getOrElse(cuw.callerId, getEmptyEntityWarningStatus)
      val newUws = uws.copy(status = updateUnwarningDetail(cuw.unwarnFrom, cuw.unwarnPeriod, uws.status))
      entityWarningStatus = entityWarningStatus + (cuw.callerId -> newUws)

    case cruw: CallerResourceUnwarned =>
      val uws = entityWarningStatus.getOrElse(cruw.callerId, EntityWarningStatus(buildEmptyWarningDetail, Map.empty))
      val urwd = uws.resourcesStatus.getOrElse(cruw.resourceName, buildEmptyWarningDetail)
      val newUrws = uws.resourcesStatus + (cruw.resourceName -> updateUnwarningDetail(cruw.unwarnFrom, cruw.unwarnPeriod, urwd))
      val newUws = uws.copy(resourcesStatus = newUrws)
      entityWarningStatus = entityWarningStatus + (cruw.callerId -> newUws)
  }

  /**
   * returns filtered entities who has 'warned' or 'unwarned' resources
   * @param wl warning list (warned & unwarned both)
   * @param curDateTimeOpt optional, Some(date) if active list is required else None
   * @return
   */
  def filterAllResources(wl: Map[EntityId, EntityWarningStatus], curDateTimeOpt: Option[ZonedDateTime] = None):
  Map[EntityId, EntityWarningStatus] = {

    //first filter all active resources
    val filteredByActiveRsrc = wl.map { urw =>
      val filtered = urw._2.resourcesStatus.filter { case (_, wd) =>
        curDateTimeOpt.forall { cdt =>
          wd.isWarned(cdt) || wd.isUnwarned(cdt)
        }
      }
      urw._1 -> urw._2.copy(resourcesStatus = filtered)
    }

    // now filter all active entities (which are either warned/unwarned or has NON empty active resources)
    filteredByActiveRsrc.filter { case (_, ur) =>
      curDateTimeOpt.forall { cdt =>
        ur.status.isWarned(cdt) ||
          ur.status.isUnwarned(cdt) ||
          ur.resourcesStatus.nonEmpty
      }
    }
  }


  /**
   * returns filtered entities who has 'warned' resources
   * @param wl warning list (warned & unwarned)
   * @param curDateTimeOpt optional, Some(date) if active list is required else None
   * @return
   */
  def filterWarnedUserResources(wl: Map[EntityId, EntityWarningStatus], curDateTimeOpt: Option[ZonedDateTime] = None):
  Map[EntityId, EntityWarningStatus] = {
    val filteredByWarnedRsrc = wl.map { urw =>
      val filtered = urw._2.resourcesStatus.filter { case (_, wd) =>
        curDateTimeOpt.map { cdt =>
          wd.isWarned(cdt)
        }.getOrElse {
          wd.warnFrom.isDefined
        }
      }
      urw._1 -> urw._2.copy(resourcesStatus = filtered)
    }
    filteredByWarnedRsrc.filter { case (_, ur) =>
      curDateTimeOpt.map { cdt =>
        ur.status.isWarned(cdt)
      }.getOrElse {
        ur.status.warnFrom.isDefined
      } || ur.resourcesStatus.nonEmpty
    }
  }

  /**
   * returns filtered entities who has 'unwarned' resources
   * @param wl warning list (warned & unwarned)
   * @param curDateTimeOpt optional, Some(date) if active list is required else None
   * @return
   */
  def filterUnwarnedUserResources(wl: Map[EntityId, EntityWarningStatus], curDateTimeOpt: Option[ZonedDateTime]):
  Map[EntityId, EntityWarningStatus] = {
    val filteredByUnwarnedRsrc = wl.map { urw =>
      val filtered = urw._2.resourcesStatus.filter { case (_, wd) =>
        curDateTimeOpt.map { cdt =>
          wd.isUnwarned(cdt)
        }.getOrElse {
          wd.unwarnFrom.isDefined
        }
      }
      urw._1 -> urw._2.copy(resourcesStatus = filtered)
    }
    filteredByUnwarnedRsrc.filter { case (_, ur) =>
      curDateTimeOpt.map { cdt =>
        ur.status.isUnwarned(cdt)
      }.getOrElse {
        ur.status.unwarnFrom.isDefined
      } || ur.resourcesStatus.nonEmpty
    }
  }

  def getOnlyWarned(onlyActive: Boolean=true):  Map[EntityId, EntityWarningStatus] = {
    val curDateTime = if (onlyActive) Option(getCurrentUTCZonedDateTime) else None
    filterWarnedUserResources(entityWarningStatus, curDateTime)
  }

  def getOnlyUnwarned(onlyActive: Boolean=true):  Map[EntityId, EntityWarningStatus] = {
    val curDateTime = if (onlyActive) Option(getCurrentUTCZonedDateTime) else None
    filterUnwarnedUserResources(entityWarningStatus, curDateTime)
  }

  def getAll(onlyActive: Boolean=true):  Map[EntityId, EntityWarningStatus] = {
    val curDateTime = if (onlyActive) Option(getCurrentUTCZonedDateTime) else None
    filterAllResources(entityWarningStatus, curDateTime)
  }

  def updateWarningDetail(warnFrom: Long, warnPeriod: Long, toWarningDetail: WarningDetail): WarningDetail = {
    val warnedFromDateTime = getZonedDateTimeFromMillis(warnFrom)
    val warnTillDateTime = getZonedDateTimeFromPeriod(warnedFromDateTime, warnPeriod)
    toWarningDetail.copy(warnFrom=Option(warnedFromDateTime), warnTill = warnTillDateTime,
      unwarnFrom = None, unwarnTill = None)
  }

  def updateUnwarningDetail(unwarnFrom: Long, unwarnPeriod: Long, toWarningDetail: WarningDetail): WarningDetail = {
    val unwarnFromDateTime = getZonedDateTimeFromMillis(unwarnFrom)
    val unwarnTillDateTime = getZonedDateTimeFromPeriod(unwarnFromDateTime, unwarnPeriod)
    toWarningDetail.copy(unwarnFrom=Option(unwarnFromDateTime), unwarnTill = unwarnTillDateTime,
      warnFrom = None, warnTill = None)
  }

  def buildEmptyWarningDetail: WarningDetail =  WarningDetail(None, None, None, None)

  def getEmptyEntityWarningStatus: EntityWarningStatus = EntityWarningStatus(buildEmptyWarningDetail, Map.empty)

}

