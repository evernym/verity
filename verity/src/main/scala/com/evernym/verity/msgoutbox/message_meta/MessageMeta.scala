package com.evernym.verity.msgoutbox.message_meta

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityContext, EntityTypeKey}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.evernym.verity.msgoutbox.message_meta.Events.LegacyMsgData
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Replies.Msg
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.States.{Initialized, Processed}
import com.evernym.verity.msgoutbox.{MsgId, OutboxId, RecipPackaging}
import com.evernym.verity.actor.typed.base.PersistentEventAdapter
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.config.ConfigConstants.SALT_EVENT_ENCRYPTION
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Commands.MsgStoreReplyAdapter
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.did.DidStr
import com.evernym.verity.msgoutbox.outbox.Outbox
import com.evernym.verity.util.TimeZoneUtil
import com.evernym.verity.util2.{RetentionPolicy, Status}
import java.time.ZonedDateTime

import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger


object MessageMeta {

  //commands
  trait Cmd extends ActorMessage
  sealed trait ProcessedStateCmd extends Cmd
  sealed trait UninitializedStateCmd extends Cmd
  sealed trait InitializedStateCmd extends Cmd

  object Commands {
    case class Get(replyTo: ActorRef[Replies.GetMsgReply]) extends ProcessedStateCmd with UninitializedStateCmd with InitializedStateCmd
    case class Add(`type`: String,
                   retentionPolicy: String, //TODO: finalize format etc
                   targetOutboxIds: Set[String],
                   legacyMsgData: Option[LegacyMsgData]=None, //TODO: needs to finalize need of this
                   recipPackaging: Option[RecipPackaging], //TODO: needs to finalize need of this
                   replyTo: ActorRef[Replies.AddMsgReply]) extends InitializedStateCmd with UninitializedStateCmd

    case class RecordMsgActivity(outboxId: String,
                                 deliveryStatus: String,
                                 msgActivity: Option[MsgActivity]) extends InitializedStateCmd

    case class ProcessedForOutbox(outboxId: String,
                                  deliveryStatus: String,
                                  msgActivity: Option[MsgActivity],
                                  replyTo: ActorRef[ProcessedForOutboxReply]) extends ProcessedStateCmd with InitializedStateCmd

    case class GetDeliveryStatus(replyTo: ActorRef[Replies.MsgDeliveryStatus]) extends ProcessedStateCmd with InitializedStateCmd

    case class MsgStoreReplyAdapter(reply: MsgStore.GetPayloadReply) extends InitializedStateCmd
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
  sealed trait ProcessedForOutboxReply extends Reply
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

    case class RemoveMsg(msgId: MsgId) extends ProcessedForOutboxReply
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
  case class LegacyData(senderDID: DidStr, refMsgId: Option[MsgId])
  case class OutboxDeliveryStatus(isProcessed: Boolean = false,
                                  status: String = Status.MSG_DELIVERY_STATUS_PENDING.statusCode,
                                  msgActivities: Seq[MsgActivity] = List.empty)
  case class MsgActivity(detail: String, timestamp: Option[ZonedDateTime]=None)

  // behaviour
  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("MessageMeta")
  private val logger: Logger = getLoggerByClass(getClass)

  def apply(entityContext: EntityContext[Cmd],
            msgStore: ActorRef[MsgStore.Cmd],
            apConfig: AppConfig): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>

      val eventEncryptionSalt = apConfig.getStringReq(SALT_EVENT_ENCRYPTION)

      val msgStoreReplyAdapter = actorContext.messageAdapter(reply => MsgStoreReplyAdapter(reply))

      EventSourcedBehavior
        .withEnforcedReplies(
          PersistenceId(TypeKey.name, entityContext.entityId),
          States.Uninitialized,
          commandHandler(entityContext.entityId, msgStore)(actorContext, msgStoreReplyAdapter),
          eventHandler)
        .receiveSignal(signalHandler(entityContext.entityId, msgStore)(actorContext, msgStoreReplyAdapter))
        .eventAdapter(PersistentEventAdapter(entityContext.entityId, EventObjectMapper, eventEncryptionSalt))
    }
  }

  private def commandHandler(msgId: MsgId,
                             msgStoreAdapter: ActorRef[MsgStore.Cmd])
                            (implicit context: ActorContext[Cmd],
                             msgStoreReplyAdapter: ActorRef[MsgStore.GetPayloadReply]): (State, Cmd) => ReplyEffect[Event, State] = {

    case (States.Uninitialized, cmd: UninitializedStateCmd) => uninitializedStateHandler(cmd)
    case (st: States.Initialized, cmd: InitializedStateCmd) => initializedStateHandler(msgId, msgStoreAdapter, st)(context, msgStoreReplyAdapter)(cmd)
    case (st: States.Processed, cmd: ProcessedStateCmd) => processedStateHandler(msgId, st)(cmd)
    case (st, cmd) =>
      logger.warn(s"Received unexpected message ${cmd} in state ${st}")
      Effect.noReply
  }

  private def uninitializedStateHandler: UninitializedStateCmd => ReplyEffect[Event, State] = {
    case Commands.Get(replyTo) =>
      Effect
        .reply(replyTo)(Replies.MsgNotYetAdded)

    case c: Commands.Add =>
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
        .thenReply(c.replyTo)(_ => Replies.MsgAdded)
  }

  private def initializedStateHandler(
                                       msgId: MsgId,
                                       msgStoreAdapter: ActorRef[MsgStore.Cmd],
                                       st: States.Initialized
                                     )(
                                       implicit context: ActorContext[Cmd],
                                       msgStoreReplyAdapter: ActorRef[MsgStore.GetPayloadReply]
                                     ): InitializedStateCmd => ReplyEffect[Event, State] = {
    case c: Commands.Add =>
      Effect
        .reply(c.replyTo)(Replies.MsgAlreadyAdded)

    case Commands.Get(replyTo) =>
      Effect
        .reply(replyTo)(st.msgDetail.buildMsg(msgId))

    case Commands.GetDeliveryStatus(replyTo) =>
      Effect
        .reply(replyTo)(Replies.MsgDeliveryStatus(isProcessed = false, st.deliveryStatus))

    case rma: Commands.RecordMsgActivity =>
      val msgActivityRecorded = {
        val msgActivity = rma.msgActivity.map { ma =>
          Events.MsgActivity(ma.detail, Option(TimeZoneUtil.getMillisForCurrentUTCZonedDateTime))
        }
        Events.MsgActivityRecorded(rma.outboxId, isProcessed = false, rma.deliveryStatus, msgActivity)
      }
      Effect
        .persist(msgActivityRecorded)
        .thenNoReply()

    case pfo: Commands.ProcessedForOutbox =>
      if (! st.deliveryStatus.get(pfo.outboxId).exists(_.isProcessed)) {
        val msgActivityRecorded = {
          val msgActivity = pfo.msgActivity.map { ma =>
            Events.MsgActivity(ma.detail, Option(TimeZoneUtil.getMillisForCurrentUTCZonedDateTime))
          }
          Events.MsgActivityRecorded(pfo.outboxId, isProcessed = true, pfo.deliveryStatus, msgActivity)
        }
        Effect
          .persist(msgActivityRecorded)
          .thenRun { (state: State) =>
            val sentForDeletion = deletePayloadIfRequired(msgId, msgStoreAdapter, state)
            if (!sentForDeletion) pfo.replyTo ! Replies.RemoveMsg(msgId)
          }
          .thenNoReply()
      } else Effect.noReply

    case Commands.MsgStoreReplyAdapter(MsgStore.Replies.PayloadDeleted) =>
      Effect
        .persist(Events.PayloadDeleted())
        .thenRun{ (_: State) =>
          st.deliveryStatus.keySet.foreach { outboxId =>
            val entityRef = ClusterSharding(context.system).entityRefFor(Outbox.TypeKey, outboxId)
            entityRef ! Outbox.Commands.RemoveMsg(msgId)
          }
        }
        .thenNoReply()
  }

  private def processedStateHandler(msgId: MsgId, st: States.Processed): ProcessedStateCmd => ReplyEffect[Event, State] = {
    case pfo: Commands.ProcessedForOutbox =>
      Effect
        .reply(pfo.replyTo)(Replies.RemoveMsg(msgId))

    case Commands.GetDeliveryStatus(replyTo) =>
      Effect
        .reply(replyTo)(Replies.MsgDeliveryStatus(isProcessed = true, st.deliveryStatus))

    case Commands.Get(replyTo) =>
      Effect
        .reply(replyTo)(st.msgDetail.buildMsg(msgId))
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
          isProcessed = dsa.isProcessed,
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
                            msgStoreReplyAdapter: ActorRef[MsgStore.GetPayloadReply]): PartialFunction[(State, Signal), Unit] = {
    case (st: States.Initialized, RecoveryCompleted) =>
      deletePayloadIfRequired(msgId, msgStoreAdapter, st)
  }

  private def deletePayloadIfRequired(msgId: MsgId,
                                      msgStore: ActorRef[MsgStore.Cmd],
                                      st: State)
                                     (implicit actorContext: ActorContext[Cmd],
                                      msgStoreReplyAdapter: ActorRef[MsgStore.GetPayloadReply]): Boolean = {
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

