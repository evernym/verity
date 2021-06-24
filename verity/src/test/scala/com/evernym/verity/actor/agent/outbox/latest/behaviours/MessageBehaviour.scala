package com.evernym.verity.actor.agent.outbox.latest.behaviours

import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.typed.base.{BehaviourUtil, EventSourcedBehaviorBuilder}


object MessageBehaviour {

  trait Cmd
  object Commands {
    case class Get(replyTo: ActorRef[StatusReply[RespMsg]]) extends Cmd
  }

  trait State
  object States {
    case object Uninitialized extends State
  }

  trait RespMsg extends ActorMessage
  object RespMsgs {
    case object MsgNotAdded extends RespMsg
  }

  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Message")

  def apply(entityContext: EntityContext[Cmd]): Behavior[Cmd] = {
    EventSourcedBehaviorBuilder
      .default(PersistenceId(TypeKey.name, entityContext.entityId), States.Uninitialized, commandHandler, eventHandler)
      .build()
  }

  private def commandHandler(util: BehaviourUtil): (State, Cmd) => ReplyEffect[Any, State] = {
    case (States.Uninitialized, Commands.Get(replyTo)) =>
      Effect.reply(replyTo)(StatusReply.success(RespMsgs.MsgNotAdded))
  }

  private val eventHandler: (State, Any) => State = {
    case (_, _) => throw new RuntimeException("not implemented")
  }
}

