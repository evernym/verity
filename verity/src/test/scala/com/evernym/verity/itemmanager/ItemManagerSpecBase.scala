//package com.evernym.verity.itemmanager
//
//import com.evernym.verity.actor.ForIdentifier
//import com.evernym.verity.actor.cluster_singleton.watcher.ActorWatcher.itemManagerEntityIdPrefix
//import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId}
//import com.evernym.verity.actor.itemmanager.ItemConfigManager.versionedItemManagerEntityId
//import com.evernym.verity.actor.itemmanager.{ExternalCmdWrapper, ItemContainerMapper}
//import com.evernym.verity.actor.persistence.SnapshotConfig
//import com.evernym.verity.actor.testkit.PersistentActorSpec
//import com.evernym.verity.testkit.BasicSpec
//import com.typesafe.config.{Config, ConfigFactory}
//import org.scalatest.concurrent.Eventually
//
//import java.time.LocalDateTime
//
//
//trait ItemManagerSpecBase
//  extends PersistentActorSpec
//    with BasicSpec
//    with Eventually {
//
//  final val ITEM_ID_1 = "123"
//  final val ITEM_ID_2 = "242"
//  final val ITEM_ID_3 = "335"
//  final val ITEM_ID_4 = "584"
//
//  final val ORIG_ITEM_DETAIL = "detail"
//  final val UPDATED_ITEM_DETAIL = "detail updated"
//
//  val itemManagerEntityId1: String = versionedItemManagerEntityId(itemManagerEntityIdPrefix, appConfig)
//
//  implicit val persistenceConfig: SnapshotConfig = SnapshotConfig (
//    snapshotEveryNEvents = None,
//    deleteEventsOnSnapshot = true,
//    keepNSnapshots = Option(1))
//
//  var itemContainerEntityDetails: Map[ItemId, ItemContainerEntityDetail] = Map.empty
//
//  def getLastKnownItemContainerEntityId(itemId: ItemId): ItemContainerEntityId =
//    itemContainerEntityDetails(itemId).latestEntityId
//
//  def getOriginalItemContainerEntityId(itemId: ItemId): ItemContainerEntityId =
//    itemContainerEntityDetails(itemId).originalEntityId
//
//  def updateLatestItemContainerEntityId(itemId: ItemId, newItemContainerId: ItemContainerEntityId): Unit = {
//    val updated = itemContainerEntityDetails
//      .getOrElse(itemId, ItemContainerEntityDetail(newItemContainerId, newItemContainerId))
//      .copy(latestEntityId=newItemContainerId)
//    itemContainerEntityDetails = itemContainerEntityDetails + (itemId -> updated)
//  }
//
//  def prepareExternalCmdWrapper(cmd: Any): ExternalCmdWrapper = ExternalCmdWrapper(cmd, None)
//
//  def sendExternalCmdToItemManager(toId: ItemContainerEntityId, cmd: Any): Unit = {
//    sendCmdToItemManager(toId, prepareExternalCmdWrapper(cmd))
//  }
//
//  def sendCmdToItemManager(toId: ItemContainerEntityId, cmd: Any): Unit = {
//    platform.itemManagerRegion ! ForIdentifier(toId, cmd)
//  }
//
//  def sendCmdToItemContainer(toId: ItemContainerEntityId, cmd: Any): Unit = {
//    platform.itemContainerRegion ! ForIdentifier(toId, cmd)
//  }
//
//  def sendExternalCmdToItemContainer(toId: ItemContainerEntityId, cmd: Any): Unit = {
//    platform.itemContainerRegion ! ForIdentifier(toId, ExternalCmdWrapper(cmd, None))
//  }
//
//  case class ItemContainerEntityDetail(originalEntityId: ItemContainerEntityId, latestEntityId: ItemContainerEntityId)
//
//  def watcherConfig: Config =
//    ConfigFactory parseString {
//      s"""
//        |verity {
//        |  actor.watcher {
//        |    scheduled-job {
//        |      interval-in-seconds = -1
//        |    }
//        |  }
//        |
//        |  item-container {
//        |    mapper.class = "com.evernym.verity.itemmanager.TestTimeBasedItemContainerMapper"
//        |
//        |    scheduled-job {
//        |      interval-in-seconds = 1
//        |    }
//        |
//        |    migration {
//        |      chunk-size = 20
//        |    }
//        |  }
//        |}
//        |""".stripMargin
//    }
//
//}
//
//class TestTimeBasedItemContainerMapper extends ItemContainerMapper {
//
//  def getItemContainerId(itemId: ItemId): ItemContainerEntityId = {
//    val ldTime = LocalDateTime.now()
//    val paddedMonth = ldTime.getMonthValue.toString.reverse.padTo(2, '0').reverse
//    val paddedDay = ldTime.getDayOfMonth.toString.reverse.padTo(2, '0').reverse
//    val paddedHour = ldTime.getHour.toString.reverse.padTo(2, '0').reverse
//    val paddedMinutes = ldTime.getMinute.toString.reverse.padTo(2, '0').reverse
//    val paddedSeconds = ldTime.getSecond.toString.reverse.padTo(2, '0').reverse
//    s"${ldTime.getYear}$paddedMonth$paddedDay$paddedHour$paddedMinutes$paddedSeconds"
//  }
//}