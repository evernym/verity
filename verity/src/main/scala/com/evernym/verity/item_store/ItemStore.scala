package com.evernym.verity.item_store

import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.evernym.verity.actor.{ActorMessage, RetentionCriteriaBuilder}
import com.evernym.verity.actor.typed.base.{PersistentEventAdapter, PersistentStateAdapter}
import com.evernym.verity.config.ConfigConstants.SALT_EVENT_ENCRYPTION
import com.evernym.verity.item_store.ItemStore.Replies.{ItemAdded, ItemRemoved, Items}
import com.typesafe.config.Config

object ItemStore {

  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("ItemStore")

  trait Cmd extends ActorMessage
  object Commands {
    case class AddItem(id: ItemId, detail: String, replyTo: ActorRef[Replies.ItemAdded]) extends Cmd
    case class RemoveItem(id: ItemId, replyTo: ActorRef[Replies.ItemRemoved]) extends Cmd
    case class Get(replyTo: ActorRef[Replies.Items]) extends Cmd
  }
  trait Reply extends ActorMessage
  object Replies {
    case class ItemAdded(id: ItemId) extends Reply
    case class ItemRemoved(id: ItemId) extends Reply
    case class Items(active: List[Item]) extends Reply
  }

  trait Event   //all events would be defined in item-store-events.proto file
  trait State   //all events would be defined in item-store-states.proto file

  def apply(entityId: String,
            config: Config): Behavior[Cmd] = {

    val retentionCriteria =
      RetentionCriteriaBuilder
        .build(
          config,
          snapshotConfigPath = "verity.item-store.snapshot",
          defaultSnapAfterEveryEvent = 100,
          defaultKeepSnapshots = 1,
          defaultDeleteEventsOnSnapshot = true
        )

    val encryptionSalt = config.getString(SALT_EVENT_ENCRYPTION)
    EventSourcedBehavior
      .withEnforcedReplies(
        PersistenceId(TypeKey.name, entityId),
        States.Ready(),
        commandHandler(),
        eventHandler())
      .receiveSignal(signalHandler())
      .eventAdapter(PersistentEventAdapter(entityId, EventObjectMapper, encryptionSalt))
      .snapshotAdapter(PersistentStateAdapter(entityId, StateObjectMapper, encryptionSalt))
      .withRetention(retentionCriteria)
  }

  private def commandHandler(): (State, Cmd) => ReplyEffect[Event, State] = {
    case (s: States.Ready, Commands.AddItem(id, detail, replyTo)) =>
      if (s.items.contains(id)) {
        Effect
          .reply(replyTo)(ItemAdded(id))
      } else {
        Effect
          .persist(Events.ItemAdded(id, detail))
          .thenReply(replyTo)(_ => ItemAdded(id))
      }

    case (s: States.Ready, Commands.RemoveItem(id, replyTo)) =>
      if (s.items.contains(id)) {
        Effect
          .persist(Events.ItemRemoved(id))
          .thenReply(replyTo)(_ => ItemRemoved(id))
      } else {
        Effect
          .reply(replyTo)(ItemRemoved(id))
      }

    case (s: States.Ready, Commands.Get(replyTo)) =>
      val all = s.items.map{ case (id, detail) => Item(id, detail) }.toList
      Effect
        .reply(replyTo)(Items(all))
  }

  private def eventHandler(): (State, Event) => State = {
    case (s: States.Ready, Events.ItemAdded(id, detail)) =>
      s.copy(s.items + (id -> detail))

    case (s: States.Ready, Events.ItemRemoved(id)) =>
      s.copy(s.items - id)
  }

  private def signalHandler(): PartialFunction[(State, Signal), Unit] = {
    case (_: State, RecoveryCompleted) => //nothing
  }

}

case class Item(id: ItemId, detail: String)