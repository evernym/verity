package com.evernym.verity.itemmanager

import akka.actor.ActorRef
import akka.cluster.sharding.ShardRegion.EntityId
import com.evernym.verity.actor._
import com.evernym.verity.actor.base.EntityIdentifier
import com.evernym.verity.actor.cluster_singleton.watcher.AgentActorWatcher.itemManagerEntityIdPrefix
import com.evernym.verity.actor.itemmanager.ItemCommonConstants._
import com.evernym.verity.actor.itemmanager.ItemConfigManager.versionedItemManagerEntityId
import com.evernym.verity.actor.itemmanager._
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.typesafe.config.Config
import org.scalatest.time.{Millis, Seconds, Span}


class TimeBasedMovingItemManagerSpec
  extends ItemManagerSpecBase {

  override lazy val overrideConfig: Option[Config] = Option {
    watcherConfig
  }

  final val itemManagerId = versionedItemManagerEntityId(itemManagerEntityIdPrefix, appConfig)

  "ItemManager" - {

    "when sent 'SetItemManagerConfig'" - {
      "should respond 'ItemManagerConfigAlreadySet'" in {
        eventually(timeout(Span(5, Seconds)), interval(Span(200, Millis))) {
          sendExternalCmdToItemManager(itemManagerEntityId1, SetItemManagerConfig(itemManagerId,
            migrateItemsToNextLinkedContainer = true))
          expectMsgPF() {
            case ItemManagerConfigAlreadySet =>
              checkItemManagerEntityId(lastSender)
          }
        }
      }
    }

    "when sent 'UpdateItem' " - {
      "should respond 'ItemCmdResponse'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_1, detailOpt=Option(ORIG_ITEM_DETAIL)))
        expectMsgPF() {
          case ItemCmdResponse(iu: ItemUpdated, senderEntityId) if iu.status == ITEM_STATUS_ACTIVE =>
            checkItemManagerEntityId(lastSender)
            checkItemContainerEntityId(senderEntityId)
            updateLatestItemContainerEntityId(ITEM_ID_1, senderEntityId)
        }
      }
    }

    "when sent 'GetState' after adding one item" - {
      "should respond 'ItemManagerStateDetail'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, GetState)
        expectMsgPF() {
          case ims: ItemManagerStateDetail if ims.headContainerEntityId.isDefined && ims.tailContainerEntityId.isDefined &&
            ims.headContainerEntityId == ims.tailContainerEntityId =>
            checkItemManagerEntityId(lastSender)
        }
      }
    }

    "when sent 'UpdateItem' for new item" - {
      "should respond 'ItemCmdResponse'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_2))
        expectMsgPF() {
          case ItemCmdResponse(iu: ItemUpdated, senderEntityId) if iu.status == ITEM_STATUS_ACTIVE =>
            checkItemManagerEntityId(lastSender)
            checkItemContainerEntityId(senderEntityId)
            updateLatestItemContainerEntityId(ITEM_ID_2, senderEntityId)
        }
      }
    }

    "when sent 'GetState' after adding new item" - {
      "should respond 'ItemManagerStateDetail'" in {
        eventually(timeout(Span(5, Seconds))) {
          sendExternalCmdToItemManager(itemManagerEntityId1, GetState)
          expectMsgPF() {
            case bs: ItemManagerStateDetail if bs.headContainerEntityId.isDefined && bs.tailContainerEntityId.isDefined &&
              bs.headContainerEntityId == bs.tailContainerEntityId =>
              checkItemManagerEntityId(lastSender)
          }
        }
      }
    }
  }

  "ItemContainer" - {
    "when sent 'GetItem'" - {
      "should return appropriate value" in {
        //the item may be in active or migrated state in last known container
        sendExternalCmdToItemContainer(getLastKnownItemContainerEntityId(ITEM_ID_1), GetItem(ITEM_ID_1))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE|ITEM_STATUS_MIGRATED, _, Some(ORIG_ITEM_DETAIL)), senderEntityId) =>
            checkItemContainerEntityId(senderEntityId)
        }

        //the item in the latest container should be always in active state
        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some(ORIG_ITEM_DETAIL)), senderEntityId) =>
            checkItemContainerEntityId(lastSender)
            checkItemContainerEntityId(senderEntityId)
            updateLatestItemContainerEntityId(ITEM_ID_1, senderEntityId)
        }
      }
    }
  }

  "ItemManager" - {
    "when sent 'UpdateItem' for previously saved item with new detail" - {
      "should respond 'ItemCmdResponse'" in {
        getLastKnownItemContainerEntityId(ITEM_ID_1)
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_1, detailOpt=Option(UPDATED_ITEM_DETAIL)))
        expectMsgPF() {
          //if it is migrated
          case ItemCmdResponse(ItemUpdated(ITEM_ID_1, ITEM_STATUS_ACTIVE, UPDATED_ITEM_DETAIL, true, _), senderEntityId) =>
            checkItemManagerEntityId(lastSender)
            checkItemContainerEntityId(senderEntityId)
            updateLatestItemContainerEntityId(ITEM_ID_1, senderEntityId)

          //if it is not migrated (but it may have been directly sent to a new item container)
          case ItemCmdResponse(ItemUpdated(ITEM_ID_1, ITEM_STATUS_ACTIVE, UPDATED_ITEM_DETAIL, false, _), senderEntityId) =>
            checkItemManagerEntityId(lastSender)
            checkItemContainerEntityId(senderEntityId)
            updateLatestItemContainerEntityId(ITEM_ID_1, senderEntityId)
        }
      }
    }
  }

  "ItemContainer" - {
    "when sent 'GetItem' for id 1" - {
      "should return updated value" in {
        sendExternalCmdToItemContainer(getLastKnownItemContainerEntityId(ITEM_ID_1), GetItem(ITEM_ID_1))
        //the item may be in active or migrated state in last known container
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE|ITEM_STATUS_MIGRATED, _, Some(UPDATED_ITEM_DETAIL)), senderEntityId) =>
            checkItemContainerEntityId(senderEntityId)
        }

        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
        //the item in latest container should be always in active state
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some(UPDATED_ITEM_DETAIL)), senderEntityId) =>
            checkItemContainerEntityId(lastSender)
            checkItemContainerEntityId(senderEntityId)
            updateLatestItemContainerEntityId(ITEM_ID_1, senderEntityId)
        }
      }
    }

    "when sent 'GetItem' for id 2" - {
      "should return appropriate value" in {
        val item2LatestContainerId = getLastKnownItemContainerEntityId(ITEM_ID_2)
        sendExternalCmdToItemContainer(item2LatestContainerId, GetItem(ITEM_ID_2))
        //the item may be in active or migrated state in last known container
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_2, ITEM_STATUS_ACTIVE|ITEM_STATUS_MIGRATED, _, None), senderEntityId) =>
            checkItemContainerEntityId(senderEntityId)
        }

        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_2))
        //the item in latest container should be always in active state
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_2, ITEM_STATUS_ACTIVE, _, None), senderEntityId) =>
            checkItemContainerEntityId(lastSender)
            checkItemContainerEntityId(senderEntityId)
            updateLatestItemContainerEntityId(ITEM_ID_2, senderEntityId)
        }
      }
    }

    "when sent 'GetItem' for id 3" - {
      "should return updated value" in {
        sendExternalCmdToItemContainer(getLastKnownItemContainerEntityId(ITEM_ID_2), GetItem(ITEM_ID_3))
        expectMsgPF() {
          case ItemCmdResponse(_: ItemNotFound, senderEntityId)=>
            checkItemContainerEntityId(senderEntityId)
        }

        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_3))
        expectMsgPF() {
          case ItemCmdResponse(_: ItemNotFound, _) =>
            checkItemContainerEntityId(lastSender)
        }
      }
    }
  }

  "ItemManager" - {
    "when sent 'GetItem' for id 2" - {
      "should have moved to new item container" taggedAs UNSAFE_IgnoreAkkaEvents in {
        //Note: eventually item should be moved to latest container
        val item2OriginalContainerEntityId = getOriginalItemContainerEntityId(ITEM_ID_2)
        eventually(timeout(Span(15, Seconds)), interval(Span(200, Millis))) {

          sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_2))
          expectMsgPF() {
            case ItemCmdResponse(ItemDetailResponse(ITEM_ID_2, ITEM_STATUS_ACTIVE, true, None), senderEntityId)
              if item2OriginalContainerEntityId != senderEntityId =>
              checkItemContainerEntityId(lastSender)
              checkItemContainerEntityId(senderEntityId)
              updateLatestItemContainerEntityId(ITEM_ID_2, senderEntityId)
          }
        }
        //Note: and then previous obsolete container should have cleaned up its storage
        val item2PostMigrationContainerEntityId = getLastKnownItemContainerEntityId(ITEM_ID_2)
        eventually(timeout(Span(15, Seconds)), interval(Span(200, Millis))) {
          sendExternalCmdToItemContainer(item2PostMigrationContainerEntityId, GetState)
          expectMsgPF() {
            case ics: ItemContainerState
              if ics.migratedContainers.nonEmpty &&
                ics.migratedContainers.get(item2OriginalContainerEntityId).exists(_.isStorageCleaned) =>
                  //previous item container's storage cleaned
          }
        }
      }
    }

    "when sent 'GetItem' for id 1" - {
      "should return value" in {
        eventually (timeout(Span(5, Seconds))) {
          sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
          expectMsgPF() {
            case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some(UPDATED_ITEM_DETAIL)), senderEntityId)
              if getOriginalItemContainerEntityId(ITEM_ID_1) != senderEntityId =>
              checkItemContainerEntityId(lastSender)
              checkItemContainerEntityId(senderEntityId)
          }
        }
      }
    }

    "when sent 'GetState'" - {
      "should respond 'ItemManagerStateDetail'" taggedAs UNSAFE_IgnoreAkkaEvents in {
        eventually(timeout(Span(7, Seconds))) {
          sendExternalCmdToItemManager(itemManagerEntityId1, GetState)
          expectMsgPF() {
            case bs: ItemManagerStateDetail if bs.headContainerEntityId.isDefined && bs.tailContainerEntityId.isDefined &&
              bs.headContainerEntityId == bs.tailContainerEntityId =>
              checkItemManagerEntityId(lastSender)
          }
        }
      }
    }

    "when sent 'GetState' again" - {
      "should respond 'ItemManagerStateDetail'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, GetState)
        expectMsgPF() {
          case bs: ItemManagerStateDetail if bs.headContainerEntityId.isDefined && bs.tailContainerEntityId.isDefined &&
            bs.headContainerEntityId == bs.tailContainerEntityId =>
            checkItemManagerEntityId(lastSender)
        }
      }
    }

    "when sent 'GetAllContainerEntityIds'" - {
      "should respond with 'AllContainerIds'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, GetActiveContainers)
        expectMsgPF() {
          case acs: ActiveContainerStatus if acs.containers.keySet.size == 1 =>
        }
      }
    }

    "when sent 'GetAllItems'" - {
      "should response with 'AllItems'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, GetItems(Set.empty))
        expectMsgPF() {
          case aci: AllItems if aci.items.size == 2 =>
        }
      }
    }
  }

  private def checkItemManagerEntityId(senderActorRef: ActorRef): Unit = {
    val senderEntityId = EntityIdentifier.parsePath(senderActorRef.path).entityId
    senderEntityId shouldBe "watcher-v2"
  }

  private def checkItemContainerEntityId(senderActorRef: ActorRef): Unit = {
    checkItemContainerEntityId(EntityIdentifier.parsePath(senderActorRef.path).entityId)
  }

  private def checkItemContainerEntityId(senderEntityId: EntityId): Unit = {
    val (prefix, suffix) = senderEntityId.splitAt(senderEntityId.lastIndexOf("-")+1)
    (prefix == "watcher-v2-" && suffix.toLong > 1) shouldBe true
  }
}
