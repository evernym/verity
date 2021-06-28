package com.evernym.verity.actor.agent.outbox_behaviours.outbox

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.pattern.StatusReply
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import com.evernym.RetentionPolicy
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.actor.agent.outbox_behaviours.message.MessageBehaviour.{LegacyData, RespMsg}
import com.evernym.verity.actor.agent.outbox_behaviours.message.{EventObjectMapper, Events, MessageBehaviour}
import com.evernym.verity.actor.agent.outbox_behaviours.outbox.ReadOnlyMessageBehaviour.{Cmd, Msg}
import com.evernym.verity.actor.agent.outbox_behaviours.outbox.ReadOnlyMessageBehaviour.Commands.{Get, Initialize}
import com.evernym.verity.actor.typed.base.EventPersistenceAdapter
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.protocol.engine.MsgId
import com.evernym.verity.storage_services.{BucketLifeCycleUtil, StorageAPI}

object ReadOnlyMessageBehaviour {

  trait GetMsgRespBase extends RespMsg
  case class Msg(`type`: String,
                 legacyData: Option[LegacyData],
                 policy: RetentionPolicy,
                 payloadStorageInfo: StorageInfo,
                 payload: Option[Array[Byte]] = None) extends GetMsgRespBase

  trait Cmd
  object Commands {
    case class Initialize(msg: Msg, payload: Option[Array[Byte]]) extends Cmd
    case class Get(replyTo: ActorRef[StatusReply[GetMsgRespBase]]) extends Cmd
  }

  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("ReadOnlyMessage")

  trait State

  object States {
    case object Uninitialized extends State
    case class Initializing(msg: Msg) extends State
    case class Initialized(msg: Msg) extends State
  }

  def apply(msgId: MsgId,
            bucketName: String,
            storageAPI: StorageAPI): Behavior[Cmd] = {
    Behaviors.withStash(10) { buffer =>
      Behaviors.setup { context =>
        uninitialized(buffer, context, msgId, bucketName, storageAPI)
      }
    }
  }

  private def uninitialized(buffer: StashBuffer[Cmd],
                            context: ActorContext[Cmd],
                            msgId: MsgId,
                            bucketName: String,
                            storageAPI: StorageAPI): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {
    case cmd: Get =>
      context.spawn(ReadOnlyMessageCreator(msgId, bucketName, storageAPI, context.self), msgId)
      buffer.stash(cmd)
      initializing(buffer)
  }

  private def initializing(buffer: StashBuffer[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {
    case Initialize(msg, payload) =>
      buffer.unstashAll(initialized(msg.copy(payload = payload)))
    case _ =>
      Effect.stash()
      Behaviors.same
  }

  private def initialized(msg: Msg): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {
    case Get(replyTo) =>
      replyTo ! StatusReply.success(msg)
      Behaviors.same
  }
}


//==================================================================================================

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
private object ReadOnlyMessageCreator {
  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("ReadOnlyMessageCreator")

  trait State
  object States {
    case object Uninitialized extends State
    case class Initialized(msg: Msg) extends State
  }

  def apply(msgId: MsgId,
            bucketName: String,
            storageAPI: StorageAPI,
            callerRef: ActorRef[Cmd]): Behavior[Cmd] = {
    Behaviors.setup { context =>
      EventSourcedBehavior(
        PersistenceId(MessageBehaviour.TypeKey.name, msgId),
        States.Uninitialized,
        commandHandler,
        eventHandler)
        .eventAdapter(new EventPersistenceAdapter(msgId, EventObjectMapper))
        .receiveSignal(signalHandler(callerRef, msgId, bucketName, storageAPI))
    }
  }

  private def commandHandler: (State, Cmd) => Effect[Any, State] = {
    case (st: State, cmd) => throw new RuntimeException("cmd not supported: " + cmd)
  }

  private val eventHandler: (State, Any) => State = {
    case (States.Uninitialized, Events.MsgAdded(typ, legacyMsgData, policy, Some(storageInfo), outboxIds)) =>
      States.Initialized(Msg(typ, legacyMsgData.map(LegacyData(_)),
        ConfigUtil.getPolicyFromConfigStr(policy), storageInfo))
    case (st: States.Initialized, event) =>
      //for this read only behaviour we are not interested in any other event (as of writing this)
      st
  }

  private def signalHandler(callerRef: ActorRef[Cmd],
                            msgId: MsgId,
                            bucketName: String,
                            storageAPI: StorageAPI): PartialFunction[(State, Signal), Unit] = {

    case (st: States.Initialized, RecoveryCompleted) =>
      val lifeCycleAddress = BucketLifeCycleUtil.lifeCycleAddress(Option(st.msg.policy.elements.expiryDaysStr), msgId)
      storageAPI.get(bucketName, lifeCycleAddress).map { payload =>
        callerRef ! Initialize(st.msg, payload)
        Behaviors.stopped
      }
  }
}
