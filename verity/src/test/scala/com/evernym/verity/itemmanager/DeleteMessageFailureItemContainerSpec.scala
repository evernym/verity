package com.evernym.verity.itemmanager


import com.evernym.verity.actor._
import com.evernym.verity.actor.itemmanager.ItemCommonConstants._
import com.evernym.verity.actor.itemmanager._
import com.evernym.verity.actor.testkit.{AkkaTestBasic, AppStateManagerTestKit, PersistentActorSpec}
import com.evernym.verity.actor.testkit.checks.{IgnoreLog, UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor.appStateManager.state.ListeningState
import com.evernym.verity.actor.cluster_singleton.watcher.AgentActorWatcher.itemManagerEntityIdPrefix
import com.evernym.verity.actor.itemmanager.ItemConfigManager.versionedItemManagerEntityId
import com.evernym.verity.util.TimeZoneUtil.getCurrentUTCZonedDateTime
import com.typesafe.config.Config
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.Future


class DeleteMessageFailureItemManagerSpec
  extends PersistentActorSpec
    with ItemManagerSpecBase
    with SystemExitSpec {

  val asmTestKit = new AppStateManagerTestKit(this, appConfig)

  override lazy val overrideConfig: Option[Config] = Option {
    watcherConfig
      .withFallback(configForDeleteEventFailure)
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
          }
        }
      }
    }

    "when sent 'UpdateItem' " - {
      "should respond 'ItemCmdResponse'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_1, detailOpt=Option("test")))
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
  }

  "ItemContainer" - {
    "when sent 'GetItem'" - {
      "should return appropriate value" in {
        sendExternalCmdToItemContainer(getLastKnownItemContainerEntityId(ITEM_ID_1), GetItem(ITEM_ID_1))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some("test")), _) =>
        }
        sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
        expectMsgPF() {
          case ItemCmdResponse(ItemDetailResponse(ITEM_ID_1, ITEM_STATUS_ACTIVE, _, Some("test")), senderEntityId) =>
            updateLatestItemContainerEntityId(ITEM_ID_1, senderEntityId)
        }
      }
    }
  }

  "ItemManager" - {
    "when sent 'SaveItem' for new item" - {
      "should respond 'ItemCmdResponse'" in {
        sendExternalCmdToItemManager(itemManagerEntityId1, UpdateItem(ITEM_ID_2))
        expectMsgPF() {
          case ItemCmdResponse(iu: ItemUpdated, senderEntityId) if iu.status == ITEM_STATUS_ACTIVE =>
            updateLatestItemContainerEntityId(ITEM_ID_2, senderEntityId)
        }
      }
    }

    //TODO: this commented test and the below one both can't be tested right now
    //due to app state manager being singleton and no ability of resetting it's state.
    //for now, kept this as commented, and keeping the below one (that is the main one) uncommented.
    //    "when sent 'GetItem' for id 1 during app initialization state" - {
    //      "should have moved to new item container" in {
    //        //note: eventually during item container cleanup, if delete events fail (which we are doing in test)
    //        //it should call app state manager with SeriousSystemError event which ultimately shutdowns the service
    //        //by calling System.exit
    //        exitSecurityManager.exitCallCount shouldBe 0
    //        eventually(timeout(Span(10, Seconds))) {
    //          sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
    //          exitSecurityManager.exitCallCount shouldBe 1
    //        }
    //      }
    //    }

    "when sent 'GetItem' for id 1 during 'ListeningSuccessful' app state" - {
      "app state manager should not change state" taggedAs (IgnoreLog, UNSAFE_IgnoreLog, UNSAFE_IgnoreAkkaEvents) in {
        exitSecurityManager.exitCallCount shouldBe 0
        asmTestKit.withListeningAppState() {
          //note: eventually, during item container cleanup, if delete events fail (which we are doing in test)
          //it should NOT change app state
          eventually(timeout(Span(10, Seconds)), interval(Span(200, Millis))) {
            sendExternalCmdToItemManager(itemManagerEntityId1, GetItem(ITEM_ID_1))
            expectMsgType[ItemCmdResponse]
            asmTestKit.checkAppManagerState(ListeningState)
          }
        }
      }
    }
  }

  def configForDeleteEventFailure: Config =  {
    AkkaTestBasic.customJournal("com.evernym.verity.itemmanager.FailsOnDeleteEventsTestJournal")
  }
}


class FailsOnDeleteEventsTestJournal extends TestJournal {
  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    Future.failed(new RuntimeException(s"PURPOSEFULLY failing in test (thrown at = $getCurrentUTCZonedDateTime)"))
  }
}
