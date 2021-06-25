package com.evernym.verity.actor.agent.outbox_behaviours.message

import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import com.evernym.RetentionPolicy
import com.evernym.verity.actor.agent.outbox_behaviours.message.MessageBehaviour.RespMsgs.MsgAdded
import com.evernym.verity.actor.typed.base.{EventTransformer, EventSourcedBehaviorBuilder}
import com.evernym.verity.actor.{ActorMessage, StorageInfo}
import com.evernym.verity.config.ConfigUtil


object MessageBehaviour {

  trait Cmd
  object Commands {
    case class Get(replyTo: ActorRef[StatusReply[RespMsg]]) extends Cmd
    case class Add(`type`: String,
                   payloadStorageInfo: StorageInfo,
                   policy: RetentionPolicy,
                   replyTo: ActorRef[StatusReply[RespMsg]]) extends Cmd
  }

  trait State
  object States {
    case object Uninitialized extends State
    case class Added(`type`: String, policy: RetentionPolicy, payloadStorageInfo: StorageInfo) extends State
  }

  trait RespMsg extends ActorMessage
  object RespMsgs {
    case object MsgNotAdded extends RespMsg
    case object MsgAdded extends RespMsg
    case object MsgAlreadyAdded extends RespMsg
  }

  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Message")

  def apply(entityContext: EntityContext[Cmd]): Behavior[Cmd] = {
    EventSourcedBehaviorBuilder
      .default(
        PersistenceId(TypeKey.name, entityContext.entityId),
        States.Uninitialized,
        commandHandler,
        eventHandler)
      .withEventCodeMapper(EventObjectMapper)
      .build()
  }

  private def commandHandler(eventTransformer: EventTransformer): (State, Cmd) => ReplyEffect[Any, State] = {
    case (States.Uninitialized, Commands.Get(replyTo)) =>
      Effect.reply(replyTo)(StatusReply.success(RespMsgs.MsgNotAdded))

    case (States.Uninitialized, c: Commands.Add) =>
      eventTransformer
        .persist(Added(c.`type`, c.policy.configString, Option(c.payloadStorageInfo)))
        .thenReply(c.replyTo)(_ => StatusReply.success(MsgAdded))

    case (_: States.Added, c: Commands.Add) =>
      Effect.reply(c.replyTo)(StatusReply.success(RespMsgs.MsgAlreadyAdded))
  }

  private val eventHandler: (State, Any) => State = {
    case (States.Uninitialized, Added(typ, policy, Some(storageInfo)))  =>
      States.Added(typ, ConfigUtil.getPolicyFromConfigStr(policy), storageInfo)
  }
}
