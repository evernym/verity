package com.evernym.verity.itemmanager

import com.evernym.verity.Exceptions.InvalidValueException
import com.evernym.verity.actor._
import com.evernym.verity.actor.itemmanager.ItemCommonConstants._
import com.evernym.verity.actor.itemmanager.{ItemManagerConfigNotYetSet, _}
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.typesafe.config.Config
import org.scalatest.time.{Seconds, Span}


class TimeBasedMovingItemManagerSpec extends ItemManagerSpecBase {

  override def overrideConfig: Option[Config] = Option {
    watcherConfig
  }

  final val ITEM_TYPE = "uap-convo"

  "ItemConfigProvider" - {
    "when tried to add a mapper" - {
      "should be able to add it" in {
        ItemConfigManager.addNewItemContainerMapper(ITEM_TYPE,
          TestTimeBasedItemContainerMapper(ENTITY_ID_MAPPER_VERSION_V1))
        ItemConfigManager.addNewItemContainerMapper(ITEM_TYPE,
          TestTimeBasedItemContainerMapper(LATEST_ITEM_ACTOR_ENTITY_ID_MAPPER_VERSION + 1))
      }
    }
    "when tried to add same mapper again" - {
      "it should not change any state as such" in {
        val thrown = intercept[InvalidValueException] {
          ItemConfigManager.addNewItemContainerMapper(ITEM_TYPE,
            TestTimeBasedItemContainerMapper(LATEST_ITEM_ACTOR_ENTITY_ID_MAPPER_VERSION))
        }
        thrown.respMsg.exists(s => s.contains("duplicate mappers not allowed")) shouldBe true
      }
    }
    "when tried to add new mapper with invalid value" - {
      "should throw proper error" in {
        val thrown1 = intercept[InvalidValueException] {
          ItemConfigManager.addNewItemContainerMapper(ITEM_TYPE,
            TestTimeBasedItemContainerMapper(0))
        }
        thrown1.respMsg.exists(s => s.contains("non sequential version ids not allowed")) shouldBe true

        val thrown2 = intercept[InvalidValueException] {
          ItemConfigManager.addNewItemContainerMapper(ITEM_TYPE,
            TestTimeBasedItemContainerMapper(LATEST_ITEM_ACTOR_ENTITY_ID_MAPPER_VERSION + 5))
        }
        thrown2.respMsg.exists(s => s.contains("non sequential version ids not allowed")) shouldBe true
      }
    }
  }

  "ItemManager" - {
    "when sent 'UpdateItem' before setting required config" - {
      "should respond 'HandledErrorException'" taggedAs (UNSAFE_IgnoreLog) in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_1, detailOpt=Option(ORIG_ITEM_DETAIL)))
        expectMsgPF() {
          case ItemManagerConfigNotYetSet =>
        }
      }
    }

    "when sent 'SetItemManagerConfig'" - {
      "should respond 'ItemManagerStateDetail'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, SetItemManagerConfig(ITEM_TYPE, ITEM_OWNER_VER_KEY,
          migrateItemsToNextLinkedContainer = true, migrateItemsToLatestVersionedContainers = false))
        expectMsgPF() {
          case _: ItemManagerStateDetail =>
        }
      }
    }

    "when sent 'UpdateItem' " - {
      "should respond 'ItemCmdResponse'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_1, detailOpt=Option(ORIG_ITEM_DETAIL)))
        expectMsgPF() {
          case ItemCmdResponse(iu: ItemUpdated, senderEntityId) if iu.status == ITEM_STATUS_ACTIVE =>
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
        }
      }
    }

    "when sent 'UpdateItem' for new item" - {
      "should respond 'ItemCmdResponse'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_2))
        expectMsgPF() {
          case ItemCmdResponse(iu: ItemUpdated, senderEntityId) if iu.status == ITEM_STATUS_ACTIVE =>
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
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE|ITEM_STATUS_MIGRATED, _, Some(ORIG_ITEM_DETAIL)), _) =>
        }

        //the item in latest container should be always in active state
        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some(ORIG_ITEM_DETAIL)), senderEntityId) =>
            updateLatestItemContainerEntityId(ITEM_ID_1, senderEntityId)
        }
      }
    }
  }

  "ItemManager" - {
    "when sent 'UpdateItem' for previously saved item with new detail" - {
      "should respond 'ItemCmdResponse'" in {
        val item1PreviousContainerId = getLastKnownItemContainerEntityId(ITEM_ID_1)
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_1, detailOpt=Option(UPDATED_ITEM_DETAIL)))
        expectMsgPF() {
          //if it is migrated
          case ItemCmdResponse(ItemUpdated(ITEM_ID_1, ITEM_STATUS_ACTIVE, UPDATED_ITEM_DETAIL, true, _), senderEntityId) =>
            updateLatestItemContainerEntityId(ITEM_ID_1, senderEntityId)

          //if it is not migrated (but it may have been directly sent to a new item container)
          case ItemCmdResponse(ItemUpdated(ITEM_ID_1, ITEM_STATUS_ACTIVE, UPDATED_ITEM_DETAIL, false, _), senderEntityId) =>
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
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE|ITEM_STATUS_MIGRATED, _, Some(UPDATED_ITEM_DETAIL)), _) =>
        }

        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
        //the item in latest container should be always in active state
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some(UPDATED_ITEM_DETAIL)), senderEntityId) =>
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
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_2, ITEM_STATUS_ACTIVE|ITEM_STATUS_MIGRATED, _, None), _) =>
        }

        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_2))
        //the item in latest container should be always in active state
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_2, ITEM_STATUS_ACTIVE, _, None), senderEntityId) =>
              updateLatestItemContainerEntityId(ITEM_ID_2, senderEntityId)
        }
      }
    }

    "when sent 'GetItem' for id 3" - {
      "should return updated value" in {
        sendExternalCmdToItemContainer(getLastKnownItemContainerEntityId(ITEM_ID_2), GetItem(ITEM_ID_3))
        expectMsgPF() {
          case ItemCmdResponse(_: ItemNotFound, _)=>
        }

        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_3))
        expectMsgPF() {
          case ItemCmdResponse(_: ItemNotFound, _) =>
        }
      }
    }
  }

  "ItemManager" - {
    "when sent 'GetItem' for id 2" - {
      "should have moved to new item container" taggedAs (UNSAFE_IgnoreAkkaEvents) in {
        //Note: eventually item should be moved to latest container
        val item2OriginalContainerEntityId = getOriginalItemContainerEntityId(ITEM_ID_2)
        eventually(timeout(Span(15, Seconds)), interval(Span(3, Seconds))) {

          sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_2))
          expectMsgPF() {
            case ItemCmdResponse(ItemDetailResponse(ITEM_ID_2, ITEM_STATUS_ACTIVE, true, None), senderEntityId)
              if item2OriginalContainerEntityId != senderEntityId =>
                updateLatestItemContainerEntityId(ITEM_ID_2, senderEntityId)
          }
        }
        //Note: and then previous obsolete container should have cleaned up its storage
        val item2PostMigrationContainerEntityId = getLastKnownItemContainerEntityId(ITEM_ID_2)
        eventually(timeout(Span(15, Seconds)), interval(Span(3, Seconds))) {
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
          }
        }
      }
    }

    "when sent 'GetState'" - {
      "should respond 'ItemManagerStateDetail'" taggedAs (UNSAFE_IgnoreAkkaEvents) in {
        eventually(timeout(Span(7, Seconds))) {
          sendExternalCmdToItemManager(itemManagerEntityId1, GetState)
          expectMsgPF() {
            case bs: ItemManagerStateDetail if bs.headContainerEntityId.isDefined && bs.tailContainerEntityId.isDefined &&
              bs.headContainerEntityId == bs.tailContainerEntityId =>
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
}
