package com.evernym.verity.itemmanager

import com.evernym.verity.Exceptions.InvalidValueException
import com.evernym.verity.actor._
import com.evernym.verity.actor.itemmanager.ItemCommonConstants._
import com.evernym.verity.actor.itemmanager._
import com.typesafe.config.Config
import org.scalatest.time.{Seconds, Span}


class ShardBasedItemManagerSpec extends ItemManagerSpecBase {

  override def overrideConfig: Option[Config] = Option {
    watcherConfig
  }

  final val ITEM_TYPE = "agent-routing-table"
  final val AGENT_ROUTING_TOTAL_CONTAINERS = 100

  "ItemConfigProvider" - {
    "when tried to add a mapper" - {
      "should be able to add it" in {
        ItemConfigManager.addNewItemContainerMapper(ITEM_TYPE,
          ShardBasedItemContainerMapper(ENTITY_ID_MAPPER_VERSION_V1, AGENT_ROUTING_TOTAL_CONTAINERS))
      }
    }
    "when tried to add same mapper again" - {
      "it should not change any state as such" in {
        val thrown = intercept[InvalidValueException] {
          ItemConfigManager.addNewItemContainerMapper(ITEM_TYPE,
            ShardBasedItemContainerMapper(ENTITY_ID_MAPPER_VERSION_V1, AGENT_ROUTING_TOTAL_CONTAINERS))
        }
        thrown.respMsg.exists(s => s.contains("duplicate mappers not allowed")) shouldBe true
      }
    }
    "when tried to add new mapper with invalid value" - {
      "should throw proper error" in {
        val thrown1 = intercept[InvalidValueException] {
          ItemConfigManager.addNewItemContainerMapper(ITEM_TYPE,
            ShardBasedItemContainerMapper(0, AGENT_ROUTING_TOTAL_CONTAINERS))
        }
        thrown1.respMsg.exists(s => s.contains("non sequential version ids not allowed")) shouldBe true

        val thrown2 = intercept[InvalidValueException] {
          ItemConfigManager.addNewItemContainerMapper(ITEM_TYPE,
            ShardBasedItemContainerMapper(LATEST_ITEM_ACTOR_ENTITY_ID_MAPPER_VERSION + 2, AGENT_ROUTING_TOTAL_CONTAINERS))
        }
        thrown2.respMsg.exists(s => s.contains("non sequential version ids not allowed")) shouldBe true
      }
    }
  }

  "ItemManager" - {
    "when sent 'SaveItem' before setting required config" - {
      "should respond 'ItemManagerConfigNotYetSet'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_1, detailOpt=Option(ORIG_ITEM_DETAIL)))
        expectMsgPF() {
          case ItemManagerConfigNotYetSet =>
        }
      }
    }

    "when sent 'SetItemManagerConfig'" - {
      "should respond 'ItemManagerStateDetail'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, SetItemManagerConfig(ITEM_TYPE, ITEM_OWNER_VER_KEY,
          migrateItemsToNextLinkedContainer = false, migrateItemsToLatestVersionedContainers = true))
        expectMsgPF() {
          case _: ItemManagerStateDetail =>
        }
      }
    }

    "when resent 'SetItemManagerConfig'" - {
      "should respond 'ItemManagerConfigAlreadySet'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, SetItemManagerConfig(ITEM_TYPE, ITEM_OWNER_VER_KEY,
          migrateItemsToNextLinkedContainer = false, migrateItemsToLatestVersionedContainers = true))
        expectMsgPF() {
          case ItemManagerConfigAlreadySet =>
        }
      }
    }

    "when sent 'SaveItem' " - {
      "should respond 'ItemUpdated'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_1, detailOpt=Option(ORIG_ITEM_DETAIL)))
        expectMsgPF() {
          case ItemCmdResponse(_:ItemUpdated, senderEntityId) =>
            updateLatestItemContainerEntityId(ITEM_ID_1, senderEntityId)
        }
      }
    }

    "when sent 'GetItemManagerState' after adding one item" - {
      "should respond 'ItemManagerStateDetail'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, GetState)
        expectMsgPF() {
          case ims: ItemManagerStateDetail
            if ims.headContainerEntityId.isDefined &&
              ims.tailContainerEntityId.isDefined &&
              ims.headContainerEntityId == ims.tailContainerEntityId =>
        }
      }
    }

    "when sent 'SaveItem' for new item" - {
      "should respond 'ItemUpdated'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_2))
        expectMsgPF() {
          case ItemCmdResponse(_:ItemUpdated, senderEntityId) =>
            updateLatestItemContainerEntityId(ITEM_ID_2, senderEntityId)
        }
      }
    }

    "when sent 'GetItemManagerState' after adding new item" - {
      "should respond 'ItemManagerStateDetail'" in {
        eventually {
          sendExternalCmdToItemManager(itemManagerEntityId1, GetState)
          expectMsgPF() {
            case bs: ItemManagerStateDetail if bs.headContainerEntityId.isDefined && bs.tailContainerEntityId.isDefined &&
              bs.headContainerEntityId != bs.tailContainerEntityId =>
          }
        }
      }
    }
  }

  "ItemContainer" - {
    "when sent 'GetItem'" - {
      "should return appropriate value" in {
        sendExternalCmdToItemContainer(getLastKnownItemContainerEntityId(ITEM_ID_1), GetItem(ITEM_ID_1))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some(ORIG_ITEM_DETAIL)), _) =>
        }

        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some(ORIG_ITEM_DETAIL)), _) =>
        }
      }
    }
  }

  "ItemManager" - {
    "when sent 'SaveItem' again with new detail" - {
      "should respond 'ItemCmdResponse'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_1, detailOpt=Option(UPDATED_ITEM_DETAIL)))
        expectMsgPF() {
          case bis: ItemCmdResponse => updateLatestItemContainerEntityId(ITEM_ID_1, bis.senderEntityId)
        }
      }
    }
  }

  "ItemContainer" - {
    "when sent 'GetItem' for id 1" - {
      "should return updated value" in {
        sendExternalCmdToItemContainer(getLastKnownItemContainerEntityId(ITEM_ID_1), GetItem(ITEM_ID_1))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some(UPDATED_ITEM_DETAIL)), _) =>
        }

        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some(UPDATED_ITEM_DETAIL)), _) =>
        }
      }
    }

    "when sent 'GetItem' for id 2" - {
      "should return appropriate value" in {
        sendExternalCmdToItemContainer(getLastKnownItemContainerEntityId(ITEM_ID_2), GetItem(ITEM_ID_2))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_2, ITEM_STATUS_ACTIVE, _, None), _) =>
        }

        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_2))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_2, ITEM_STATUS_ACTIVE, _, None), _) =>
        }
      }
    }

    "when sent 'GetItem' for id 3" - {
      "should return updated value" in {
        sendExternalCmdToItemContainer(getLastKnownItemContainerEntityId(ITEM_ID_2), GetItem(ITEM_ID_3))
        expectMsgPF() {
          case ItemCmdResponse(_:ItemNotFound, _) =>
        }

        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_3))
        expectMsgPF() {
          case ItemCmdResponse(_: ItemNotFound, senderEntityId) =>
            updateLatestItemContainerEntityId(ITEM_ID_3, senderEntityId)
        }
      }
    }
  }

  "ItemManager" - {

    "when tried to add new mapper version" - {
      "should be able to add it" in {
        ItemConfigManager.addNewItemContainerMapper(ITEM_TYPE,
          ShardBasedItemContainerMapper(LATEST_ITEM_ACTOR_ENTITY_ID_MAPPER_VERSION+1, AGENT_ROUTING_TOTAL_CONTAINERS + 53))
      }
    }

    "when sent 'GetItem' for id 2" - {
      "should return value from latest versioned container" in {
        //items should eventually migrated to newer versioned containers
        eventually (timeout(Span(5, Seconds))) {
          sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_2))
          expectMsgPF() {
            case ItemCmdResponse(ItemDetailResponse(ITEM_ID_2, ITEM_STATUS_ACTIVE, _, None), senderEntityId)
              if senderEntityId.startsWith(LATEST_CONFIGURED_ITEM_ACTOR_ENTITY_ID_VERSION_PREFIX) =>
          }
        }
      }
    }

    "when sent 'GetItem' for id 1" - {
      "should return value from latest versioned container" in {
        //items should eventually migrated to newer versioned containers
        eventually (timeout(Span(5, Seconds))) {
          sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
          expectMsgPF() {
            case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some(UPDATED_ITEM_DETAIL)), senderEntityId)
              if senderEntityId.startsWith(LATEST_CONFIGURED_ITEM_ACTOR_ENTITY_ID_VERSION_PREFIX) =>
          }
        }
      }
    }

    "when sent 'GetItemManagerState'" - {
      "should respond 'ItemManagerStateDetail'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, GetState)
        expectMsgPF() {
          case bs: ItemManagerStateDetail if bs.headContainerEntityId.isDefined && bs.tailContainerEntityId.isDefined &&
            bs.headContainerEntityId != bs.tailContainerEntityId =>
        }
      }
    }

    "when sent 'GetItemManagerState' again" - {
      "should respond 'ItemManagerStateDetail'" in {
        eventually(timeout(Span(5, Seconds))) {
          sendExternalCmdToItemManager(itemManagerEntityId1, GetState)
          expectMsgPF() {
            case bs: ItemManagerStateDetail if bs.headContainerEntityId.isDefined && bs.tailContainerEntityId.isDefined &&
              bs.headContainerEntityId != bs.tailContainerEntityId =>
          }
        }
      }
    }

    "when sent 'GetAllContainerEntityIds'" - {
      "should response with 'AllContainerIds'" in {
        eventually (timeout(Span(5, Seconds))) {
          sendExternalCmdToItemManager(itemManagerEntityId1, GetActiveContainers)
          expectMsgPF() {
            case acs: ActiveContainerStatus
              if acs.containers.keySet.size == 2
                && acs.containers.keySet.forall(_.startsWith(LATEST_CONFIGURED_ITEM_ACTOR_ENTITY_ID_VERSION_PREFIX)) =>
          }
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
