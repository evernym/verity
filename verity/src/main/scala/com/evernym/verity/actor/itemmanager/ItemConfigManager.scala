package com.evernym.verity.actor.itemmanager

import com.evernym.verity.Exceptions.InvalidValueException
import com.evernym.verity.actor.itemmanager.ItemCommonConstants.ENTITY_ID_MAPPER_VERSION_V1
import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId, ItemType, VersionId}
import com.evernym.verity.actor.cluster_singleton.watcher.UserAgentPairwiseActorWatcher
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.USER_AGENT_PAIRWISE_WATCHER_VERSION
import com.evernym.verity.util.TimeZoneUtil._

/**
 * implementation will decide how an item id will be mapped to a item container entity id
 * some implementation (like TimeBasedItemContainerMapper) may altogether ignore the provided itemId and
 * use other algorithm to decide where items will go
 * other implementation (like ShardBasedItemContainerMapper) may use the given itemId to decide where it should be stored.
 */
trait ItemContainerMapper {
  def versionId: VersionId
  def getItemContainerId(itemId: ItemId): ItemContainerEntityId
}

case class ShardBasedItemContainerMapper(versionId: VersionId, totalContainers: Int) extends ItemContainerMapper {
  def getItemContainerId(itemId: ItemId): ItemContainerEntityId = (math.abs(itemId.hashCode) % totalContainers).toString
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
trait ItemConfigManager {

  private var configMappersByType: Map[ItemType, Set[ItemContainerMapper]] = Map.empty

  def getMappersByType(itemType: ItemType): Set[ItemContainerMapper] =
    configMappersByType.getOrElse(itemType, Set.empty)

  def getLatestMapperByType(itemType: ItemType): ItemContainerMapper = {
    getMappersByType(itemType).maxBy(_.versionId)
  }

  def getMapperByTypeAndVersionOpt(itemType: ItemType, versionId: VersionId): Option[ItemContainerMapper] = {
    getMappersByType(itemType).find(_.versionId == versionId)
  }

  def getMapperByTypeAndVersionReq(itemType: ItemType, versionId: VersionId): ItemContainerMapper = {
    getMapperByTypeAndVersionOpt(itemType, versionId).getOrElse(
      throw new RuntimeException(s"mapper not found with type $itemType and version $versionId"))
  }

  def addNewItemContainerMapper(itemType: ItemType, mapper: ItemContainerMapper): Unit = {
    val existingItemTypeMembers = getMappersByType(itemType)
    val mappersAfterAddingNew = existingItemTypeMembers + mapper

    if (mappersAfterAddingNew.size == existingItemTypeMembers.size ||
      mappersAfterAddingNew.map(_.versionId).size != mappersAfterAddingNew.size) {
      throw new InvalidValueException(Option(s"duplicate mappers not allowed (type: $itemType)"))
    }

    mappersAfterAddingNew.toSeq.sortBy(_.versionId).zipWithIndex.foreach { case (icm, index) =>
      if (icm.versionId != index + 1) {
        throw new InvalidValueException(Option(s"non sequential version ids not allowed (type: $itemType)"))
      }
    }
    configMappersByType += (itemType -> mappersAfterAddingNew)
  }

  def buildItemContainerEntityId(itemType: ItemType, itemId: ItemId,
                                 appConfig: AppConfig,
                                 entityIdMapperVersionIdOpt: Option[VersionId]=None): ItemContainerEntityId = {
    val mapper = entityIdMapperVersionIdOpt.map(vid =>
      getMapperByTypeAndVersionReq(itemType, vid)).getOrElse(getLatestMapperByType(itemType))
    ItemConfigManager.entityId(appConfig, mapper.getItemContainerId(itemId))
  }

  def isLatestVersion(itemType: ItemType, versionId: VersionId): Boolean = {
    getMappersByType(itemType).maxBy(_.versionId).versionId == versionId
  }

  def getLatestVersion(itemType: ItemType): VersionId = getMappersByType(itemType).maxBy(_.versionId).versionId

}

object ItemConfigManager extends ItemConfigManager {
  //keep adding new mapper versions here, by default it always uses latest one for the given type
  addNewItemContainerMapper(UserAgentPairwiseActorWatcher.name, TimeBasedItemContainerMapper(ENTITY_ID_MAPPER_VERSION_V1))

  def entityIdVersionPrefix(appConfig: AppConfig): String =
    appConfig.getConfigStringReq(USER_AGENT_PAIRWISE_WATCHER_VERSION) + "-"

  def entityId(appConfig: AppConfig, entityIdSuffix: String): String = {
    entityIdVersionPrefix(appConfig) + entityIdSuffix
  }
}