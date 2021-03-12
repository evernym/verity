package com.evernym.verity.actor.itemmanager

import com.evernym.verity.Exceptions.InvalidValueException
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId, ItemType, VersionId}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util.TimeZoneUtil._

/**
 * implementation will decide how an item id will be mapped to a item container entity id
 * TimeBasedItemContainerMapper will ignore the provided itemId and use its own algorithm
 * to decide where items will go
 */
trait ItemContainerMapper {
  def versionId: VersionId
  def getItemContainerId(itemId: ItemId): ItemContainerEntityId
}

case class TimeBasedItemContainerMapper(versionId: VersionId) extends ItemContainerMapper {

  def getItemContainerId(itemId: ItemId): ItemContainerEntityId = {
    val ldTime = getCurrentUTCZonedDateTime
    val hourBlock = (0 to 23).grouped(4).toList.zipWithIndex
      .find { case (r, _) => r.contains(ldTime.getHour) }
      .map(_._2).getOrElse(-1)
    val paddedMonth = ldTime.getMonthValue.toString.reverse.padTo(2, '0').reverse
    val paddedDay = ldTime.getDayOfMonth.toString.reverse.padTo(2, '0').reverse
    s"${ldTime.getYear}$paddedMonth$paddedDay-" + hourBlock
  }
}

//NOTE: Most of the logic in this class should not be changed else it may break things
object ItemConfigManager {

  private var configMappersByType: Map[ItemType, Set[ItemContainerMapper]] = Map.empty

  def getLatestMapperByType(itemType: ItemType): ItemContainerMapper = {
    getMappersByType(itemType).maxBy(_.versionId)
  }

  def addNewItemContainerMapper(itemType: ItemType, mapper: ItemContainerMapper): Unit = {
    val existingItemTypeMembers = getMappersByType(itemType)

    if (existingItemTypeMembers.forall(_.versionId != mapper.versionId)) {
      val mappersAfterAddingNew = existingItemTypeMembers + mapper

      mappersAfterAddingNew.toSeq.sortBy(_.versionId).zipWithIndex.foreach { case (icm, index) =>
        if (icm.versionId != index + 1) {
          throw new InvalidValueException(Option(s"non sequential version ids not allowed (type: $itemType)"))
        }
      }
      configMappersByType += (itemType -> mappersAfterAddingNew)
    }
  }

  def buildItemContainerEntityId(itemType: ItemType,
                                 itemId: ItemId,
                                 appConfig: AppConfig,
                                 entityIdMapperVersionIdOpt: Option[VersionId]=None): ItemContainerEntityId = {
    val mapper = entityIdMapperVersionIdOpt.map(vid =>
      getMapperByTypeAndVersionReq(itemType, vid)).getOrElse(getLatestMapperByType(itemType))
    ItemConfigManager.entityId(appConfig, itemType) + "-" + mapper.getItemContainerId(itemId)
  }

  def isLatestVersion(itemType: ItemType, versionId: VersionId): Boolean = {
    getMappersByType(itemType).maxBy(_.versionId).versionId == versionId
  }

  def entityIdVersionPrefix(appConfig: AppConfig): String =
    appConfig.getConfigStringOption(AGENT_ACTOR_WATCHER_VERSION)
    .getOrElse("v1")

  def entityId(appConfig: AppConfig, itemType: String): String = {
    itemType + "-" + entityIdVersionPrefix(appConfig)
  }

  private def getMappersByType(itemType: ItemType): Set[ItemContainerMapper] =
    configMappersByType.getOrElse(itemType, Set.empty)

  private def getMapperByTypeAndVersionOpt(itemType: ItemType, versionId: VersionId): Option[ItemContainerMapper] = {
    getMappersByType(itemType).find(_.versionId == versionId)
  }

  private def getMapperByTypeAndVersionReq(itemType: ItemType, versionId: VersionId): ItemContainerMapper = {
    getMapperByTypeAndVersionOpt(itemType, versionId).getOrElse(
      throw new RuntimeException(s"mapper not found with type $itemType and version $versionId"))
  }
}
