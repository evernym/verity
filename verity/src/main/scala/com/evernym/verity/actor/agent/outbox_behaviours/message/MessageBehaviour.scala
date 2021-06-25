package com.evernym.verity.actor.agent.outbox_behaviours.message

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}

import com.evernym.RetentionPolicy
import com.evernym.verity.actor.agent.outbox_behaviours.message.Events.{DeliveryAttemptActivity, LegacyMsgData}
import com.evernym.verity.actor.typed.base.{EventSourcedBehaviorBuilder, EventTransformer}
import com.evernym.verity.actor.{ActorMessage, StorageInfo}
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.protocol.engine.{DID, MsgId}
import com.evernym.verity.util.TimeZoneUtil

import java.time.ZonedDateTime


object MessageBehaviour {

  trait RespMsg extends ActorMessage

  trait AddMsgRespBase extends RespMsg
  case object MsgAdded extends AddMsgRespBase
  case object MsgAlreadyAdded extends AddMsgRespBase

  case object DeliveryAttemptRecorded extends RespMsg

  case object LegacyData {
    def apply(lmd: LegacyMsgData): LegacyData = LegacyData(lmd.senderDID, lmd.refMsgId)
  }
  case class LegacyData(senderDID: DID, refMsgId: Option[MsgId])

  trait GetMsgRespBase extends RespMsg
  case object MsgNotYetAdded extends GetMsgRespBase
  case class Msg(`type`: String,
                 legacyData: Option[LegacyData],
                 policy: RetentionPolicy,
                 payloadStorageInfo: StorageInfo,
                 payload: Option[Array[Byte]] = None) extends GetMsgRespBase

  case class DeliveryActivity(timestamp: ZonedDateTime, detail: Option[String] = None)
  case class DeliveryStatus(outboxId: String,
                            comMethodId: String,
                            status: String,
                            activities: List[DeliveryActivity] = List.empty)

  case class MsgDeliveryStatus(statuses: List[DeliveryStatus]) extends RespMsg


  trait Cmd

  object Commands {
    case class Get(replyTo: ActorRef[StatusReply[GetMsgRespBase]]) extends Cmd
    case class Add(`type`: String,
                   legacyMsgData: Option[LegacyMsgData]=None,
                   retentionPolicy: RetentionPolicy,
                   payloadStorageInfo: StorageInfo,
                   replyTo: ActorRef[StatusReply[AddMsgRespBase]]) extends Cmd

    case class GetDeliveryStatus(replyTo: ActorRef[StatusReply[MsgDeliveryStatus]]) extends Cmd
    case class RecordDeliveryAttempt(outboxId: String,
                                     comMethodId: String,
                                     status: String,
                                     activity: Option[String]=None,
                                     replyTo: ActorRef[StatusReply[DeliveryAttemptRecorded.type]]) extends Cmd
    case object Stop extends Cmd
  }

  trait State
  object States {
    case object Uninitialized extends State
    case class Initialized(msg: Msg, deliveryStatus: MsgDeliveryStatus = MsgDeliveryStatus(List.empty)) extends State
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
      Effect.reply(replyTo)(StatusReply.success(MsgNotYetAdded))

    case (States.Uninitialized, c: Commands.Add) =>
      eventTransformer
        .persist(Events.MsgAdded(c.`type`, c.legacyMsgData, c.retentionPolicy.configString, Option(c.payloadStorageInfo)))
        .thenReply(c.replyTo)(_ => StatusReply.success(MsgAdded))

    case (_: States.Initialized, c: Commands.Add) =>
      Effect.reply(c.replyTo)(StatusReply.success(MsgAlreadyAdded))

    case (st: States.Initialized, Commands.Get(replyTo)) =>
      Effect.reply(replyTo)(StatusReply.success(st.msg))

    case (st: States.Initialized, Commands.GetDeliveryStatus(replyTo)) =>
      Effect.reply(replyTo)(StatusReply.success(st.deliveryStatus))

    case (st: States.Initialized, ads: Commands.RecordDeliveryAttempt) =>
      val deliveryActivity = DeliveryAttemptActivity(TimeZoneUtil.getMillisForCurrentUTCZonedDateTime, ads.activity)
      val deliveryAttemptAdded = Events.DeliveryAttemptRecorded(ads.outboxId, ads.comMethodId, ads.status, Option(deliveryActivity))
      eventTransformer
        .persist(deliveryAttemptAdded)
      Effect.reply(ads.replyTo)(StatusReply.success(DeliveryAttemptRecorded))

    case (_: State, Commands.Stop) =>
      Behaviors.stopped
      Effect.noReply
  }

  private val eventHandler: (State, Any) => State = {

    case (States.Uninitialized, Events.MsgAdded(typ, legacyMsgData, policy, Some(storageInfo)))  =>
      States.Initialized(Msg(typ, legacyMsgData.map(LegacyData(_)), ConfigUtil.getPolicyFromConfigStr(policy), storageInfo))

    case (st: States.Initialized, dsa: Events.DeliveryAttemptRecorded) =>
      val (matched, others) = st.deliveryStatus.statuses.partition(ds => ds.outboxId == dsa.outboxId && ds.comMethodId == ds.comMethodId)

      val deliveryStatus =
        matched
          .headOption.map(ds => ds.copy(status = dsa.status))
          .getOrElse(DeliveryStatus(dsa.outboxId, dsa.comMethodId, dsa.status))

      val updatedDeliveryStatus = {
        val newActivity = dsa.activity.map(a => DeliveryActivity(TimeZoneUtil.getUTCZonedDateTimeFromMillis(a.creationTimeInMillis), a.detail))
        deliveryStatus.copy(activities = deliveryStatus.activities ++ newActivity)
      }

      st.copy(deliveryStatus = MsgDeliveryStatus(others :+ updatedDeliveryStatus))
  }
}

