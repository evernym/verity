package com.evernym.verity.actor.agent.outbox_behaviours.outbox

import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.pattern.StatusReply
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import com.evernym.RetentionPolicy
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.actor.agent.outbox_behaviours.message.MessageBehaviour.{LegacyData, RespMsg}
import com.evernym.verity.actor.agent.outbox_behaviours.message.{EventObjectMapper, Events, MessageBehaviour}
import com.evernym.verity.actor.agent.outbox_behaviours.outbox.ReadOnlyMessageBehaviour.Commands.SetPayload
import com.evernym.verity.actor.agent.outbox_behaviours.outbox.ReadOnlyMessageBehaviour.States.Initialized
import com.evernym.verity.actor.typed.base.EventPersistenceAdapter
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.protocol.engine.MsgId
import com.evernym.verity.storage_services.{BucketLifeCycleUtil, StorageAPI}

import scala.util.{Failure, Success}

object ReadOnlyMessageBehaviour {

  trait GetMsgRespBase extends RespMsg
  case class Msg(`type`: String,
                 legacyData: Option[LegacyData],
                 policy: RetentionPolicy,
                 payloadStorageInfo: StorageInfo,
                 payload: Option[Array[Byte]] = None) extends GetMsgRespBase

  trait Cmd
  object Commands {
    case class SetPayload(payload: Option[Array[Byte]]) extends Cmd
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
    Behaviors.withStash(100) { buffer =>
      Behaviors.setup { context =>
        EventSourcedBehavior(
            PersistenceId(MessageBehaviour.TypeKey.name, msgId),
            States.Uninitialized,
            commandHandler(buffer),
            eventHandler)
          .eventAdapter(new EventPersistenceAdapter(msgId, EventObjectMapper))
          .receiveSignal(signalHandler(context, msgId, bucketName, storageAPI))
      }
    }
  }

  private def commandHandler(buffer: StashBuffer[Cmd]): (State, Cmd) => Effect[Any, State] = {
    case (st: States.Initializing, SetPayload(payload)) =>
      val newBehaviour: Behavior[Cmd] = readOnlyBehaviour(Initialized(st.msg.copy(payload = payload)))
      Effect.unstashAll()
      Effect.none
    case (_: States.Initializing, cmd: Cmd) =>
      Effect.stash()
      Effect.none
  }

  private val eventHandler: (State, Any) => State = {
    case (States.Uninitialized, Events.MsgAdded(typ, legacyMsgData, policy, Some(storageInfo))) =>
      States.Initializing(Msg(typ, legacyMsgData.map(LegacyData(_)),
        ConfigUtil.getPolicyFromConfigStr(policy), storageInfo))
    case (st: States.Initializing, event) =>
      //for this read only behaviour we are not interested in any other event (as of writing this)
      st
  }

  private def signalHandler(context: ActorContext[Cmd],
                            msgId: MsgId,
                            bucketName: String,
                            storageAPI: StorageAPI): PartialFunction[(State, Signal), Unit] = {

    case (st: States.Initializing, RecoveryCompleted) =>
      val lifeCycleAddress = BucketLifeCycleUtil.lifeCycleAddress(Option(st.msg.policy.elements.expiryDaysStr), msgId)
      context.pipeToSelf(storageAPI.get(bucketName, lifeCycleAddress)) {
        case Success(payloadOpt)  => SetPayload(payloadOpt)
        case Failure(exception)   => throw exception
      }
  }

  private def readOnlyBehaviour(st: Initialized): Behavior[Cmd] = {
    Behaviors.receiveMessagePartial {
      case Commands.Get(replyTo) =>
        replyTo ! StatusReply.success(st.msg)
        Behaviors.same
    }
  }
}
