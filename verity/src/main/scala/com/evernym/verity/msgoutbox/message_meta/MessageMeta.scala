package com.evernym.verity.msgoutbox.message_meta

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.evernym.verity.msgoutbox.message_meta.Events.LegacyMsgData
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Replies.Msg
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.States.{Initialized, Processed}
import com.evernym.verity.msgoutbox.{DID, MsgId, OutboxId, RecipPackaging}
import com.evernym.verity.actor.typed.base.PersistentEventAdapter
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Commands.MsgStoreReplyAdapter
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.msgoutbox.outbox.Outbox
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
                   retentionPolicy: String, //TODO: finalize format etc
                   targetOutboxIds: Set[String],
                   legacyMsgData: Option[LegacyMsgData]=None, //TODO: needs to finalize need of this
                   recipPackaging: Option[RecipPackaging], //TODO: needs to finalize need of this
                   replyTo: ActorRef[StatusReply[Replies.AddMsgReply]]) extends Cmd

    case class RecordMsgActivity(outboxId: String,
                                 deliveryStatus: String,
                                 msgActivity: Option[MsgActivity]) extends Cmd

    case class ProcessedForOutbox(outboxId: String,
                                  deliveryStatus: String,
                                  msgActivity: Option[MsgActivity],
                                  replyTo: ActorRef[Reply]) extends Cmd

    case class GetDeliveryStatus(replyTo: ActorRef[StatusReply[Replies.MsgDeliveryStatus]]) extends Cmd

    case class MsgStoreReplyAdapter(reply: MsgStore.Reply) extends Cmd
    case object TimedOut extends Cmd
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

    case class MsgDeliveryStatus(isProcessed: Boolean,
                                 outboxDeliveryStatus: Map[OutboxId, OutboxDeliveryStatus] = Map.empty) extends Reply

    case class RemoveMsg(msgId: MsgId) extends Reply
  }

  //common objects used by state and replies

  case class MsgDetail(creationTime: ZonedDateTime,
                       `type`: String,
                       retentionPolicy: RetentionPolicy,
                       recipPackaging: Option[RecipPackaging],
                       legacyData: Option[LegacyData],
                       payload: Option[Array[Byte]] = None) {

    def buildMsg(msgId: MsgId): Msg = Msg(msgId, `type`, legacyData, payload)
  }
  case object LegacyData {
    def apply(lmd: LegacyMsgData): LegacyData = LegacyData(lmd.senderDID, lmd.refMsgId)
  }
  case class LegacyData(senderDID: DID, refMsgId: Option[MsgId])
  case class OutboxDeliveryStatus(status: String = Status.MSG_DELIVERY_STATUS_PENDING.statusCode,
                                  msgActivities: Seq[MsgActivity] = List.empty)
  case class MsgActivity(detail: String, timestamp: Option[ZonedDateTime]=None)

  // behaviour
  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("MessageMeta")

  def apply(entityContext: EntityContext[Cmd],
            msgStore: ActorRef[MsgStore.Cmd],
            apConfig: AppConfig): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val msgStoreReplyAdapter = actorContext.messageAdapter(reply => MsgStoreReplyAdapter(reply))

      actorContext.setReceiveTimeout(300.seconds, Commands.TimedOut)   //TODO: finalize this
      EventSourcedBehavior
        .withEnforcedReplies(
          PersistenceId(TypeKey.name, entityContext.entityId),
          States.Uninitialized,
          commandHandler(entityContext.entityId, msgStore)(actorContext, msgStoreReplyAdapter),
          eventHandler)
        .receiveSignal(signalHandler(entityContext.entityId, msgStore)(actorContext, msgStoreReplyAdapter))
        .eventAdapter(PersistentEventAdapter(entityContext.entityId, EventObjectMapper, apConfig))
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
        .persist(
          Events.MsgAdded(
            TimeZoneUtil.getMillisForCurrentUTCZonedDateTime,
            c.`type`,
            c.retentionPolicy,
            c.targetOutboxIds.toSeq,
            c.recipPackaging,
            c.legacyMsgData
          ))
        .thenReply(c.replyTo)(_ => StatusReply.success(Replies.MsgAdded))

    case (_: States.Initialized, c: Commands.Add) =>
      Effect
        .reply(c.replyTo)(StatusReply.success(Replies.MsgAlreadyAdded))

    case (st: States.Initialized, Commands.Get(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(st.msgDetail.buildMsg(msgId)))

    case (st: States.Initialized, Commands.GetDeliveryStatus(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(Replies.MsgDeliveryStatus(isProcessed = false, st.deliveryStatus)))

    case (_: States.Initialized, rma: Commands.RecordMsgActivity) =>
      val msgActivityRecorded = {
        val msgActivity = rma.msgActivity.map { ma =>
          Events.MsgActivity(ma.detail, Option(TimeZoneUtil.getMillisForCurrentUTCZonedDateTime))
        }
        Events.MsgActivityRecorded(rma.outboxId, rma.deliveryStatus, msgActivity)
      }
      Effect
        .persist(msgActivityRecorded)
        .thenNoReply()

    case (_: States.Initialized, pfo: Commands.ProcessedForOutbox) =>
      val msgActivityRecorded = {
        val msgActivity = pfo.msgActivity.map { ma =>
          Events.MsgActivity(ma.detail, Option(TimeZoneUtil.getMillisForCurrentUTCZonedDateTime))
        }
        Events.MsgActivityRecorded(pfo.outboxId, pfo.deliveryStatus, msgActivity)
      }
      Effect
        .persist(msgActivityRecorded)
        .thenRun{ (state: State) =>
          val sentForDeletion = deletePayloadIfRequired(msgId, msgStoreAdapter, state)
          if (! sentForDeletion) pfo.replyTo ! Replies.RemoveMsg(msgId)
        }
        .thenNoReply()

    case (st: States.Initialized, Commands.MsgStoreReplyAdapter(MsgStore.Replies.PayloadDeleted)) =>
      Effect
        .persist(Events.PayloadDeleted())
        .thenRun{ (_: State) =>
          st.deliveryStatus.keySet.foreach { outboxId =>
            val entityRef = ClusterSharding(context.system).entityRefFor(Outbox.TypeKey, outboxId)
            entityRef ! Outbox.Commands.RemoveMsg(msgId)
          }
        }
        .thenNoReply()

    case (_: States.Processed, pfo: Commands.ProcessedForOutbox) =>
      Effect
        .reply(pfo.replyTo)(Replies.RemoveMsg(msgId))

    case (st: States.Processed, Commands.GetDeliveryStatus(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(Replies.MsgDeliveryStatus(isProcessed = true, st.deliveryStatus)))

    case (st: States.Processed, Commands.Get(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(st.msgDetail.buildMsg(msgId)))

    case (_: State, Commands.TimedOut) =>
      Effect
        .stop()
        .thenNoReply()
  }

  private val eventHandler: (State, Event) => State = {

    case (States.Uninitialized, ma: Events.MsgAdded)  =>
      val msgDetail = MsgDetail(
        TimeZoneUtil.getUTCZonedDateTimeFromMillis(ma.creationTimeInMillis),
        ma.`type`,
        ConfigUtil.getPolicyFromConfigStr(ma.retentionPolicy),
        ma.recipPackaging,
        ma.legacyMsgData.map(LegacyData(_))
      )
      val statusByOutboxes = ma.targetOutboxIds.map (id => id -> OutboxDeliveryStatus()).toMap
      States.Initialized(msgDetail, statusByOutboxes)

    case (st: States.Initialized, dsa: Events.MsgActivityRecorded) =>

      val updatedOutboxDeliveryStatus = {
        val newActivity = dsa.msgActivity.map(a =>
          MsgActivity(a.detail, a.creationTimeInMillis.map(TimeZoneUtil.getUTCZonedDateTimeFromMillis)))
        val outboxDeliveryStatus = st.deliveryStatus.getOrElse(dsa.outboxId, OutboxDeliveryStatus())
        outboxDeliveryStatus.copy(
          status = dsa.deliveryStatus,
          msgActivities = outboxDeliveryStatus.msgActivities ++ newActivity
        )
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
                                      msgStoreReplyAdapter: ActorRef[MsgStore.Reply]): Boolean = {
    st match {
      case i: Initialized =>
        //if message delivery status is one of the 'terminal state'
        // then only delete the payload stored in external location (s3 etc)
        val isExpired =
          i.msgDetail.creationTime.plusDays(i.msgDetail.retentionPolicy.elements.expiryDuration.toDays)
            .isBefore(TimeZoneUtil.getCurrentUTCZonedDateTime)
        val terminalStatusCode = List(Status.MSG_DELIVERY_STATUS_SENT).map(_.statusCode)
        val isProcessedForAllOutboxes = {
          i.deliveryStatus.nonEmpty &&
            i.deliveryStatus.forall { ds =>
              terminalStatusCode.contains(ds._2.status)
            }
        }

        if (isProcessedForAllOutboxes || isExpired) {
          msgStore ! MsgStore.Commands.DeletePayload(msgId, i.msgDetail.retentionPolicy, msgStoreReplyAdapter)
          true
        } else false
      case _ => false
    }
  }
}

