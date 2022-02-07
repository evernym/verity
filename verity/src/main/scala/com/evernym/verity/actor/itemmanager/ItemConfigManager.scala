//package com.evernym.verity.actor.itemmanager
//
//import akka.cluster.sharding.ShardRegion.EntityId
//import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId, ItemManagerEntityId}
//import com.evernym.verity.config.AppConfig
//import com.evernym.verity.config.ConfigConstants._
//import com.evernym.verity.util.TimeZoneUtil._
//
///**
// * implementation will decide how an item id will be mapped to a item container entity id
// * TimeBasedItemContainerMapper will ignore the provided itemId and use its own algorithm
// * to decide where items will go
// */
//trait ItemContainerMapper {
//  def getItemContainerId(itemId: ItemId): ItemContainerEntityId
//}
//
//class TimeBasedItemContainerMapper extends ItemContainerMapper {
//
//  def getItemContainerId(itemId: ItemId): ItemContainerEntityId = {
//    val ldTime = getCurrentUTCZonedDateTime
//    val hourBlock = (0 to 23).grouped(4).toList.zipWithIndex
//      .find { case (r, _) => r.contains(ldTime.getHour) }
//      .map(_._2).getOrElse(-1)
//    val paddedMonth = ldTime.getMonthValue.toString.reverse.padTo(2, '0').reverse
//    val paddedDay = ldTime.getDayOfMonth.toString.reverse.padTo(2, '0').reverse
//    s"${ldTime.getYear}$paddedMonth$paddedDay-" + hourBlock
//  }
//}
//
//////NOTE: Most of the logic in this class should not be changed else it may break things
//object ItemConfigManager {
//
//  def versionedItemManagerEntityId(itemManagerEntityId: EntityId, appConfig: AppConfig): String = {
//    itemManagerEntityId + "-" + getManagerVersionPrefix(itemManagerEntityId, appConfig)
//  }
//
//  private def getManagerVersionPrefix(itemManagerEntityId: ItemManagerEntityId, appConfig: AppConfig): String =
//    appConfig.getStringOption(s"$VERITY.item-manager.$itemManagerEntityId.version")
//      .getOrElse("v1")
//
//}
