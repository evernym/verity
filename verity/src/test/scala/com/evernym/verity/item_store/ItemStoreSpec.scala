package com.evernym.verity.item_store

import akka.actor.typed.ActorRef
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpecBase
import com.evernym.verity.item_store.ItemStore.Replies
import com.evernym.verity.item_store.ItemStore.Commands
import com.evernym.verity.item_store.ItemStore.Replies.{ItemAdded, ItemRemoved}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

import java.util.UUID

class ItemStoreSpec
  extends EventSourcedBehaviourSpecBase
    with BasicSpec
    with Eventually {

  "ItemStore" - {
    "when sent AddItem" - {
      "should respond with ItemAdded" in {
        val itemStore = createItemStore(config)
        val probe = createTestProbe[Replies.ItemAdded]()
        itemStore ! Commands.AddItem("1#123", """{"entityTypeId": 1, "entityId": "123"}""", probe.ref)
        probe.expectMessageType[ItemAdded]
      }
    }

    "when sent RemoveItem" - {
      "should respond with ItemRemoved" in {
        val itemStore = createItemStore(config)

        val probe1 = createTestProbe[Replies.ItemAdded]()
        itemStore ! Commands.AddItem("1#123", """{"entityTypeId": 1, "entityId": "123"}""", probe1.ref)

        val probe2 = createTestProbe[Replies.ItemRemoved]()
        itemStore ! Commands.RemoveItem("1#123", probe2.ref)
        probe2.expectMessageType[ItemRemoved]
      }
    }

    "when sent GetItem" - {
      "should respond with Items" in {
        val itemStore = createItemStore(config)

        val probe1 = createTestProbe[Replies.ItemAdded]()
        (1 to 10).foreach { i =>
          itemStore ! Commands.AddItem(s"1#$i", s"""{"entityTypeId": 1, "entityId": "$i"}""", probe1.ref)
          probe1.expectMessageType[ItemAdded]
        }

        val probe2 = createTestProbe[Replies.Items]()
        itemStore ! Commands.Get(probe2.ref)
        val items1 = probe2.expectMessageType[Replies.Items]
        items1.active.size shouldBe 10

        val probe3 = createTestProbe[Replies.ItemRemoved]()
        (1 to 5).foreach { i =>
          itemStore ! Commands.RemoveItem(s"1#$i", probe3.ref)
          probe3.expectMessageType[ItemRemoved]
        }

        itemStore ! Commands.Get(probe2.ref)
        val items2 = probe2.expectMessageType[Replies.Items]
        items2.active.size shouldBe 5
      }
    }
  }

  def createItemStore(config: Config): ActorRef[ItemStore.Cmd] =
    spawn(ItemStore(UUID.randomUUID().toString, config))

  lazy val config: Config = ConfigFactory.parseString(
    """
      verity {
        item-store {
          snapshot {
            after-every-events = 1
            keep-snapshots = 1
            delete-events-on-snapshots = true
          }
        }
        salt.event-encryption = "test"
      }
      """.stripMargin)
}
