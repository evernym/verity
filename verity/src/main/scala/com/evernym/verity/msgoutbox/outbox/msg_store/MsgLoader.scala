package com.evernym.verity.msgoutbox.outbox.msg_store

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.util2.RetentionPolicy
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.LegacyData
import com.evernym.verity.msgoutbox.message_meta.{Events, MessageMeta}
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgLoader.Commands.MsgStoreReplyAdapter
import com.evernym.verity.msgoutbox.{MsgId, RecipPackaging}
import com.evernym.verity.actor.typed.base.PersistentEventAdapter
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.msgoutbox.outbox.OutboxConfig

//responsible for loading message meta event from event journal
// and then go to external storage and download the message payload
object MsgLoader {

  trait Cmd extends ActorMessage

  object Commands {
    case class MsgStoreReplyAdapter(reply: MsgStore.Reply) extends Cmd
  }

  trait Reply extends ActorMessage

  object Replies {
    case class MessageMeta(msg: Msg) extends Reply
  }

  case class Msg(`type`: String,
                 policy: RetentionPolicy,
                 legacyData: Option[LegacyData],
                 recipPackaging: Option[RecipPackaging],
                 payload: Option[Array[Byte]] = None)

  trait State

  object States {
    case object Uninitialized extends State
    case class Initialized(msg: Msg) extends State
  }

  def apply(msgId: MsgId,
            msgStore: ActorRef[MsgStore.Cmd],
            replyTo: ActorRef[Reply],
            eventEncryptionSalt: String): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val msgStoreReplyAdapter = actorContext.messageAdapter(reply => MsgStoreReplyAdapter(reply))
      EventSourcedBehavior(
        PersistenceId(MessageMeta.TypeKey.name, msgId),
        States.Uninitialized,
        commandHandler(replyTo),
        eventHandler)
        .eventAdapter(new PersistentEventAdapter(msgId, com.evernym.verity.msgoutbox.message_meta.EventObjectMapper, eventEncryptionSalt))
        .receiveSignal(signalHandler(msgId, msgStore, msgStoreReplyAdapter))
    }
    //TODO: do we want to load all events or limit it to just 1st (as that much would be sufficient)
  }

  private def commandHandler(replyTo: ActorRef[Reply]): (State, Cmd) => Effect[Any, State] = {
    case (st: States.Initialized, MsgStoreReplyAdapter(reply: MsgStore.Replies.PayloadRetrieved)) =>
      replyTo ! Replies.MessageMeta(st.msg.copy(payload = reply.payload))
      Effect.stop()
  }

  private val eventHandler: (State, Any) => State = {
    case (States.Uninitialized, Events.MsgAdded(creationTimeInMillis, typ, policy, targetOutboxIds, recipPackaging, legacyMsgData)) =>
      States.Initialized(
        Msg(typ, ConfigUtil.getPolicyFromConfigStr(policy), legacyMsgData.map(LegacyData(_)), recipPackaging)
      )
    case (st: States.Initialized, event) =>
      //for this read only behaviour we are not interested in any other event (as of writing this)
      st
  }

  private def signalHandler(msgId: MsgId,
                            msgStore: ActorRef[MsgStore.Cmd],
                            msgStoreReplyAdapter: ActorRef[MsgStore.Reply]): PartialFunction[(State, Signal), Unit] = {
    case (st: States.Initialized, RecoveryCompleted) =>
      msgStore ! MsgStore.Commands.GetPayload(msgId, st.msg.policy, msgStoreReplyAdapter)
  }
}
