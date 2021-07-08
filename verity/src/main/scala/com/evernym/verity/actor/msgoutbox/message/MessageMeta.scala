package com.evernym.verity.actor.msgoutbox.message

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.evernym.verity.actor.msgoutbox.message.Events.LegacyMsgData
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.Replies.Msg
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.States.{Initialized, Processed}
import com.evernym.verity.actor.msgoutbox.outbox.{ComMethodId, DID, MsgId, OutboxId}
import com.evernym.verity.actor.typed.base.EventPersistenceAdapter
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.msgoutbox.Packaging
import com.evernym.verity.actor.msgoutbox.adapters.MsgStore
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.Commands.MsgStoreReplyAdapter
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.util.TimeZoneUtil
import com.evernym.verity.util2.{RetentionPolicy, Status}

import java.time.ZonedDateTime
import scala.concurrent.duration._


object MessageMeta {

  //commands
  trait Cmd extends ActorMessage
  object Commands {
    case class Get(replyTo: ActorRef[StatusReply[Replies.GetMsgReply]]) extends Cmd
    case class Add(`type`: String,
                   legacyMsgData: Option[LegacyMsgData]=None,
                   packaging: Option[Packaging],
                   retentionPolicy: RetentionPolicy,
                   outboxIds: Set[String],
                   replyTo: ActorRef[StatusReply[Replies.AddMsgReply]]) extends Cmd

    case class GetDeliveryStatus(replyTo: ActorRef[StatusReply[Replies.MsgDeliveryStatus]]) extends Cmd
    case class RecordDeliveryStatus(outboxId: String,
                                    deliveryStatus: String,
                                    comMethodActivity: Option[ComMethodActivity]) extends Cmd
    case class MsgStoreReplyAdapter(reply: MsgStore.Reply) extends Cmd
    case object Stop extends Cmd
  }

  //events
  trait Event   //all events would be defined in message-meta-events.proto file

  //states
  trait State
  object States {
    case object Uninitialized extends State
    case class Initialized(msgDetail: MsgDetail,
                           deliveryStatus: Map[OutboxId, OutboxDeliveryStatus] = Map.empty) extends State

    //Processed meaning the message has been processed by all the outboxes
    // (either it would have been delivered or failed after exhausted all retry attempts)
    case class Processed(msgDetail: MsgDetail,
                         deliveryStatus: Map[OutboxId, OutboxDeliveryStatus] = Map.empty) extends State
  }

  //reply messages
  trait Reply extends ActorMessage
  object Replies {
    trait AddMsgReply extends Reply
    case object MsgAdded extends AddMsgReply
    case object MsgAlreadyAdded extends AddMsgReply

    trait GetMsgReply extends Reply
    case object MsgNotYetAdded extends GetMsgReply
    case class Msg(msgId: MsgId,
                   `type`: String,
                   legacyData: Option[LegacyData],
                   payload: Option[Array[Byte]] = None) extends GetMsgReply

    case class MsgDeliveryStatus(statusByOutbox: Map[OutboxId, OutboxDeliveryStatus] = Map.empty) extends Reply
  }

  //common objects used by state and replies

  case class MsgDetail(`type`: String,
                       legacyData: Option[LegacyData],
                       packaging: Option[Packaging],
                       policy: RetentionPolicy,
                       payload: Option[Array[Byte]] = None) {

    def buildMsg(msgId: MsgId): Msg = Msg(msgId, `type`, legacyData, payload)
  }
  case object LegacyData {
    def apply(lmd: LegacyMsgData): LegacyData = LegacyData(lmd.senderDID, lmd.refMsgId)
  }
  case class LegacyData(senderDID: DID, refMsgId: Option[MsgId])
  case class OutboxDeliveryStatus(status: String = Status.MSG_DELIVERY_STATUS_PENDING.statusCode,
                                  comMethodActivities: List[ComMethodActivity] = List.empty)
  case class ComMethodActivity(comMethodId: ComMethodId, detail: String, timestamp: Option[ZonedDateTime]=None)

  // behaviour
  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("MessageMeta")

  def apply(entityContext: EntityContext[Cmd],
            msgStore: ActorRef[MsgStore.Cmd]): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val msgStoreReplyAdapter = actorContext.messageAdapter(reply => MsgStoreReplyAdapter(reply))

      actorContext.setReceiveTimeout(300.seconds, Commands.Stop)   //TODO: finalize this
      EventSourcedBehavior
        .withEnforcedReplies(
          PersistenceId(TypeKey.name, entityContext.entityId),
          States.Uninitialized,
          commandHandler(entityContext.entityId, msgStore)(actorContext, msgStoreReplyAdapter),
          eventHandler)
        .receiveSignal(signalHandler(entityContext.entityId, msgStore)(actorContext, msgStoreReplyAdapter))
        .eventAdapter(new EventPersistenceAdapter(entityContext.entityId, EventObjectMapper))
    }
  }

  private def commandHandler(msgId: MsgId,
                             msgStoreAdapter: ActorRef[MsgStore.Cmd])
                            (implicit context: ActorContext[Cmd],
                             msgStoreReplyAdapter: ActorRef[MsgStore.Reply]): (State, Cmd) => ReplyEffect[Event, State] = {

    case (States.Uninitialized, Commands.Get(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(Replies.MsgNotYetAdded))

    case (States.Uninitialized, c: Commands.Add) =>
      Effect
        .persist(Events.MsgAdded(c.`type`, c.legacyMsgData, c.packaging, c.retentionPolicy.configString, c.outboxIds.toSeq))
        .thenReply(c.replyTo)(_ => StatusReply.success(Replies.MsgAdded))

    case (_: States.Initialized, c: Commands.Add) =>
      Effect
        .reply(c.replyTo)(StatusReply.success(Replies.MsgAlreadyAdded))

    case (st: States.Initialized, Commands.Get(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(st.msgDetail.buildMsg(msgId)))

    case (st: States.Initialized, Commands.GetDeliveryStatus(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(Replies.MsgDeliveryStatus(st.deliveryStatus)))

    case (_: States.Initialized, rda: Commands.RecordDeliveryStatus) =>
      val deliveryAttemptRecorded = {
        val deliveryActivity = rda.comMethodActivity.map { cma =>
          Events.ComMethodActivity(cma.comMethodId, cma.detail, Option(TimeZoneUtil.getMillisForCurrentUTCZonedDateTime))
        }
        Events.DeliveryAttemptRecorded(rda.outboxId, rda.deliveryStatus, deliveryActivity)
      }
      Effect
        .persist(deliveryAttemptRecorded)
        .thenRun((state: State) => deletePayloadIfRequired(msgId, msgStoreAdapter, state))
        .thenNoReply()

    case (_: States.Initialized, Commands.MsgStoreReplyAdapter(MsgStore.Replies.PayloadDeleted)) =>
      Effect
        .persist(Events.PayloadDeleted())
        .thenNoReply()

    case (st: States.Processed, Commands.GetDeliveryStatus(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(Replies.MsgDeliveryStatus(st.deliveryStatus)))

    case (st: States.Processed, Commands.Get(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(st.msgDetail.buildMsg(msgId)))

    case (_: State, Commands.Stop) =>
      Effect
        .stop()
        .thenNoReply()
  }

  private val eventHandler: (State, Event) => State = {

    case (States.Uninitialized, Events.MsgAdded(typ, legacyMsgData, packaging, policy, targetOutboxIds))  =>
      val msgDetail = MsgDetail(typ, legacyMsgData.map(LegacyData(_)), packaging, ConfigUtil.getPolicyFromConfigStr(policy))
      val statusByOutboxes = targetOutboxIds.map (id => id -> OutboxDeliveryStatus()).toMap
      States.Initialized(msgDetail, statusByOutboxes)

    case (st: States.Initialized, dsa: Events.DeliveryAttemptRecorded) =>

      val updatedOutboxDeliveryStatus = {
        val newActivity = dsa.comMethodActivity.map(a =>
          ComMethodActivity(a.comMethodId, a.detail, a.creationTimeInMillis.map(TimeZoneUtil.getUTCZonedDateTimeFromMillis)))
        val outboxDeliveryStatus = st.deliveryStatus.getOrElse(dsa.outboxId, OutboxDeliveryStatus())
        outboxDeliveryStatus.copy(status = dsa.status, comMethodActivities = outboxDeliveryStatus.comMethodActivities ++ newActivity)
      }

      val updatedDeliveryStatus = st.deliveryStatus ++ Map(dsa.outboxId -> updatedOutboxDeliveryStatus)

      st.copy(deliveryStatus = updatedDeliveryStatus)

    case (st: States.Initialized, _: Events.PayloadDeleted) => Processed(st.msgDetail, st.deliveryStatus)
  }

  private def signalHandler(msgId: MsgId,
                            msgStoreAdapter: ActorRef[MsgStore.Cmd])
                           (implicit actorContext: ActorContext[Cmd],
                            msgStoreReplyAdapter: ActorRef[MsgStore.Reply]): PartialFunction[(State, Signal), Unit] = {
    case (st: States.Initialized, RecoveryCompleted) =>
      deletePayloadIfRequired(msgId, msgStoreAdapter, st)
  }

  private def deletePayloadIfRequired(msgId: MsgId,
                                      msgStore: ActorRef[MsgStore.Cmd],
                                      st: State)
                                     (implicit actorContext: ActorContext[Cmd],
                                      msgStoreReplyAdapter: ActorRef[MsgStore.Reply]): Unit = {
    st match {
      case i: Initialized =>
        //checks and delete if payload stored in external location (s3 etc) is no longer needed
        val terminalStatusCode = List(Status.MSG_DELIVERY_STATUS_SENT, Status.MSG_DELIVERY_STATUS_FAILED).map(_.statusCode)
        val isProcessedForAllOutboxes =
          i.deliveryStatus.forall{ ds =>
            terminalStatusCode.contains(ds._2.status)
          }

        if (isProcessedForAllOutboxes) {
          msgStore ! MsgStore.Commands.DeletePayload(msgId, i.msgDetail.policy, msgStoreReplyAdapter)
        }
      case _ => //nothing to do
    }
  }
}

