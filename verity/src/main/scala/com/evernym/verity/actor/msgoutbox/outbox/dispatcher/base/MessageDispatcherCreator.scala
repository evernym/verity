package com.evernym.verity.actor.msgoutbox.outbox.dispatcher.base

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import com.evernym.verity.actor.msgoutbox.adapters.MsgStore
import com.evernym.verity.actor.msgoutbox.adapters.MsgStore.Commands.GetPayload
import com.evernym.verity.actor.msgoutbox.adapters.MsgStore.Replies.PayloadRetrieved
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.LegacyData
import com.evernym.verity.actor.msgoutbox.message.{Events, MessageMeta}
import com.evernym.verity.actor.msgoutbox.outbox.MsgId
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.base.MessageDispatcher.Commands.Initialize
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.base.MessageDispatcher.Msg
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.base.MessageDispatcherCreator.Commands.MsgStoreReplyAdapter
import com.evernym.verity.actor.typed.base.EventPersistenceAdapter
import com.evernym.verity.config.ConfigUtil

//This is only used to
//  a. replay MessageMeta events and then
//  b. download the payload from msg store (s3) and then
//  c. send it back to MessageDispatcher and then stops itself
// the main thing it needs is the retention policy from event store (for now at least)
//TODO: is there any other way to remove the need of this actor?
private object MessageDispatcherCreator {

  trait Cmd
  object Commands {
    case class Payload(msg: Option[Array[Byte]]) extends Cmd
    case class MsgStoreReplyAdapter(reply: MsgStore.Reply) extends Cmd
  }

  trait State
  object States {
    case object Uninitialized extends State
    case class Initialized(msg: Msg) extends State
  }

  def apply(msgId: MsgId,
            msgStore: ActorRef[MsgStore.Cmd],
            callerRef: ActorRef[MessageDispatcher.Cmd]): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val msgStoreReplyAdapter = actorContext.messageAdapter(reply => MsgStoreReplyAdapter(reply))
      EventSourcedBehavior(
        PersistenceId(MessageMeta.TypeKey.name, msgId),
        States.Uninitialized,
        commandHandler(callerRef)(actorContext, msgStoreReplyAdapter),
        eventHandler)
        .eventAdapter(new EventPersistenceAdapter(msgId, com.evernym.verity.actor.msgoutbox.message.EventObjectMapper))
        .receiveSignal(signalHandler(msgId, msgStore)(actorContext))
    }
    //TODO: do we want to load all events or limit it to just 1st (as that much would be sufficient)
  }

  private def commandHandler(callerRef: ActorRef[MessageDispatcher.Cmd])
                            (implicit actorContext: ActorContext[Cmd],
                             msgStoreReplyAdapter: ActorRef[MsgStore.Reply]): (State, Cmd) => Effect[Any, State] = {

    case (st: States.Initialized, MsgStoreReplyAdapter(reply: PayloadRetrieved)) =>
      callerRef ! Initialize(st.msg.copy(payload = reply.payload))
      Effect.stop()
  }

  private val eventHandler: (State, Any) => State = {
    case (States.Uninitialized, Events.MsgAdded(typ, legacyMsgData, packaging, policy, targetOutboxIds)) =>
      States.Initialized(
        Msg(typ, legacyMsgData.map(LegacyData(_)), packaging, ConfigUtil.getPolicyFromConfigStr(policy))
      )
    case (st: States.Initialized, event) =>
      //for this read only behaviour we are not interested in any other event (as of writing this)
      st
  }

  private def signalHandler(msgId: MsgId,
                            msgStore: ActorRef[MsgStore.Cmd])
                           (implicit actorContext: ActorContext[Cmd]): PartialFunction[(State, Signal), Unit] = {
    case (st: States.Initialized, RecoveryCompleted) =>
      val replyToMapper = actorContext.messageAdapter(reply => MsgStoreReplyAdapter(reply))
      msgStore ! GetPayload(msgId, st.msg.policy, replyToMapper)
  }
}
