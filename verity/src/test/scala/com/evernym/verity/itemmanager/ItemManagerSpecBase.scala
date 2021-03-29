package com.evernym.verity.itemmanager

import java.time.LocalDateTime

import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.itemmanager.ItemCommonConstants.ENTITY_ID_MAPPER_VERSION_V1
import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId, VersionId}
import com.evernym.verity.actor.itemmanager.{ExternalCmdWrapper, ItemConfigManager, ItemContainerMapper}
import com.evernym.verity.actor.persistence.SnapshotConfig
import com.evernym.verity.actor.testkit.{AkkaTestBasic, PersistentActorSpec}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

trait ItemManagerSpecBase extends PersistentActorSpec with BasicSpec with Eventually {

  final val ITEM_ID_1 = "123"
  final val ITEM_ID_2 = "242"
  final val ITEM_ID_3 = "335"
  final val ITEM_ID_4 = "584"

  final val ITEM_OWNER_VER_KEY = None

  final val ORIG_ITEM_DETAIL = "detail"
  final val UPDATED_ITEM_DETAIL = "detail updated"

  def ITEM_TYPE: String

  val itemManagerEntityId1: String = ItemConfigManager.entityId(appConfig, ITEM_TYPE)

  implicit val persistenceConfig: SnapshotConfig = SnapshotConfig (
    snapshotEveryNEvents=None,
    deleteEventsOnSnapshot = true,
    keepNSnapshots = Option(1))

  var itemContainerEntityDetails: Map[ItemId, ItemContainerEntityDetail] = Map.empty

  def getLastKnownItemContainerEntityId(itemId: ItemId): ItemContainerEntityId =
    itemContainerEntityDetails(itemId).latestEntityId

  def getOriginalItemContainerEntityId(itemId: ItemId): ItemContainerEntityId =
    itemContainerEntityDetails(itemId).originalEntityId

  def updateLatestItemContainerEntityId(itemId: ItemId, newItemContainerId: ItemContainerEntityId): Unit = {
    val updated = itemContainerEntityDetails
      .getOrElse(itemId, ItemContainerEntityDetail(newItemContainerId, newItemContainerId))
      .copy(latestEntityId=newItemContainerId)
    itemContainerEntityDetails = itemContainerEntityDetails + (itemId -> updated)
  }

  def prepareExternalCmdWrapper(cmd: Any): ExternalCmdWrapper = ExternalCmdWrapper(cmd, None)

  def sendExternalCmdToItemManager(toId: ItemContainerEntityId, cmd: Any): Unit = {
    sendCmdToItemManager(toId, prepareExternalCmdWrapper(cmd))
  }

  def sendCmdToItemManager(toId: ItemContainerEntityId, cmd: Any): Unit = {
    platform.itemManagerRegion ! ForIdentifier(toId, cmd)
  }

  def sendCmdToItemContainer(toId: ItemContainerEntityId, cmd: Any): Unit = {
    platform.itemContainerRegion ! ForIdentifier(toId, cmd)
  }

  def sendExternalCmdToItemContainer(toId: ItemContainerEntityId, cmd: Any): Unit = {
    platform.itemContainerRegion ! ForIdentifier(toId, ExternalCmdWrapper(cmd, None))
  }

  case class ItemContainerEntityDetail(originalEntityId: ItemContainerEntityId, latestEntityId: ItemContainerEntityId)

  case class TestTimeBasedItemContainerMapper(versionId: VersionId) extends ItemContainerMapper {

    def getItemContainerId(itemId: ItemId): ItemContainerEntityId = {
      val ldTime = LocalDateTime.now()
      val paddedMonth = ldTime.getMonthValue.toString.reverse.padTo(2, '0').reverse
      val paddedDay = ldTime.getDayOfMonth.toString.reverse.padTo(2, '0').reverse
      val paddedHour = ldTime.getHour.toString.reverse.padTo(2, '0').reverse
      val paddedMinutes = ldTime.getMinute.toString.reverse.padTo(2, '0').reverse
      val paddedSeconds = ldTime.getSecond.toString.reverse.padTo(2, '0').reverse
      s"${ldTime.getYear}$paddedMonth$paddedDay$paddedHour$paddedMinutes$paddedSeconds"
    }
  }

  lazy val LATEST_ITEM_ACTOR_ENTITY_ID_MAPPER_VERSION = ENTITY_ID_MAPPER_VERSION_V1
  lazy val LATEST_CONFIGURED_ITEM_ACTOR_ENTITY_ID_VERSION_PREFIX =
    ItemConfigManager.entityIdVersionPrefix(appConfig)

  def configForDeleteEventFailure: Config =  {
    AkkaTestBasic.customJournal("com.evernym.verity.itemmanager.FailsOnDeleteEventsTestJournal")
  }

  def watcherConfig: Config =
    ConfigFactory parseString {
      s"""
        |verity {
        |  actor.watcher {
        |    version = v1
        |    enabled = true
        |
        |    scheduled-job {
        |      interval-in-seconds = 3
        |    }
        |  }
        |
        |  item-container {
        |
        |    scheduled-job {
        |      interval-in-seconds = 1
        |    }
        |
        |    migration {
        |      chunk-size = 20
        |    }
        |  }
        |}
        |""".stripMargin
    }

}
