package com.evernym.verity.actor.agent.outbox_behaviours.message

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import com.evernym.RetentionPolicy
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.outbox_behaviours.message.MessageBehaviour.RespMsgs.{Msg, MsgAdded}
import com.evernym.verity.actor.typed.base.{EventSourcedBehaviorBuilder, EventTransformer}
import com.evernym.verity.actor.{ActorMessage, StorageInfo}
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.protocol.engine.MsgId
import com.evernym.verity.storage_services.StorageAPI


object MessageBehaviour {

  trait Cmd
  object Commands {
    case class Get(replyTo: ActorRef[StatusReply[RespMsg]]) extends Cmd
    case class Add(`type`: String,
                   legacyMsgData: Option[LegacyMsgData]=None,
                   retentionPolicy: RetentionPolicy,
                   payloadStorageInfo: StorageInfo,
                   replyTo: ActorRef[StatusReply[RespMsg]]) extends Cmd
    case object Stop extends Cmd
  }

  trait State
  object States {
    case object Uninitialized extends State
    case class Added(`type`: String, legacyMsgData: Option[LegacyMsgData], policy: RetentionPolicy, payloadStorageInfo: StorageInfo) extends State
  }

  trait RespMsg extends ActorMessage
  object RespMsgs {
    case object MsgNotAdded extends RespMsg
    case object MsgAdded extends RespMsg
    case object MsgAlreadyAdded extends RespMsg
    case class Msg(`type`: String, legacyMsgData: Option[LegacyMsgData], payload: Option[Array[Byte]]) extends RespMsg
  }

  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Message")

  def apply(entityContext: EntityContext[Cmd],
            bucketName: String,
            storageAPI: StorageAPI): Behavior[Cmd] = {
    EventSourcedBehaviorBuilder
      .default(
        PersistenceId(TypeKey.name, entityContext.entityId),
        States.Uninitialized,
        commandHandler(entityContext.entityId, bucketName, storageAPI),
        eventHandler)
      .withEventCodeMapper(EventObjectMapper)
      .build()
  }

  private def commandHandler(msgId: MsgId,
                             bucketName: String,
                             storageAPI: StorageAPI)
                            (eventTransformer: EventTransformer): (State, Cmd) => ReplyEffect[Any, State] = {
    case (States.Uninitialized, Commands.Get(replyTo)) =>
      Effect.reply(replyTo)(StatusReply.success(RespMsgs.MsgNotAdded))

    case (States.Uninitialized, c: Commands.Add) =>
      eventTransformer
        .persist(Added(c.`type`, c.legacyMsgData, c.retentionPolicy.configString, Option(c.payloadStorageInfo)))
        .thenReply(c.replyTo)(_ => StatusReply.success(MsgAdded))

    case (_: States.Added, c: Commands.Add) =>
      Effect.reply(c.replyTo)(StatusReply.success(RespMsgs.MsgAlreadyAdded))

    case (st: States.Added, Commands.Get(replyTo)) =>
      storageAPI.get(bucketName, msgId).map { data =>
        replyTo ! StatusReply.success(Msg(st.`type`, st.legacyMsgData, data))
      }
      Effect.noReply

    case (_: State, Commands.Stop) =>
      Behaviors.stopped
      Effect.noReply
  }

  private val eventHandler: (State, Any) => State = {
    case (States.Uninitialized, Added(typ, legacyMsgData, policy, Some(storageInfo)))  =>
      States.Added(typ, legacyMsgData, ConfigUtil.getPolicyFromConfigStr(policy), storageInfo)
  }
}
