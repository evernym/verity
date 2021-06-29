package com.evernym.verity.actor.msgoutbox.outbox

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, ReplyEffect}
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.actor.typed.base.EventPersistenceAdapter

object OutboxBehaviour {

  trait Cmd
  trait Event
  trait State
  object States {
    case object Uninitialized extends State
  }

  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Outbox")

  def apply(entityContext: EntityContext[Cmd],
            walletId: String,
            destination: Destination): Behavior[Cmd] = {
    Behaviors.setup { context =>
      EventSourcedBehavior
        .withEnforcedReplies(
          PersistenceId(TypeKey.name, entityContext.entityId),
          States.Uninitialized,
          commandHandler(context, entityContext.entityId, walletId, destination),
          eventHandler)
        .eventAdapter(new EventPersistenceAdapter(entityContext.entityId, EventObjectMapper))
    }
  }

  private def commandHandler(context: ActorContext[Cmd],
                             outboxId: String,
                             walletId: String,
                             destination: Destination): (State, Cmd) => ReplyEffect[Event, State] = {
    ???
  }

  private val eventHandler: (State, Event) => State = {
    ???
  }

  case class Destination(comMethods: Set[ComMethodDetail])
}
