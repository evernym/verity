package com.evernym.verity.actor.msgoutbox.message

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.evernym.RetentionPolicy
import com.evernym.verity.Status
import com.evernym.verity.actor.msgoutbox.message.Events.{ComMethodActivity, LegacyMsgData}
import com.evernym.verity.actor.msgoutbox.message.MessageBehaviour.Commands.PayloadDeleted
import com.evernym.verity.actor.msgoutbox.message.MessageBehaviour.States.{Initialized, Processed}
import com.evernym.verity.actor.typed.base.EventPersistenceAdapter
import com.evernym.verity.actor.{ActorMessage, StorageInfo}
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.protocol.engine.{DID, MsgId}
import com.evernym.verity.storage_services.{BucketLifeCycleUtil, StorageAPI}
import com.evernym.verity.util.TimeZoneUtil

import java.time.ZonedDateTime
import scala.util.{Failure, Success}


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

  case class MsgDeliveryStatus(statusByOutbox: Map[OutboxId, OutboxDeliveryStatus] = Map.empty) extends RespMsg
  case class OutboxDeliveryStatus(status: String = Status.MSG_DELIVERY_STATUS_PENDING.statusCode,
                                  activities: List[Activity] = List.empty)
  case class Activity(comMethodId: ComMethodId, detail: String, timestamp: ZonedDateTime)

  type OutboxId = String
  type ComMethodId = String

  trait Cmd

  object Commands {
    case class Get(replyTo: ActorRef[StatusReply[GetMsgRespBase]]) extends Cmd
    case class Add(`type`: String,
                   legacyMsgData: Option[LegacyMsgData]=None,
                   retentionPolicy: RetentionPolicy,
                   payloadStorageInfo: StorageInfo,
                   outboxIds: Set[String],
                   replyTo: ActorRef[StatusReply[AddMsgRespBase]]) extends Cmd

    case class GetDeliveryStatus(replyTo: ActorRef[StatusReply[MsgDeliveryStatus]]) extends Cmd
    case class RecordDeliveryAttempt(outboxId: String,
                                     status: String,
                                     comMethodId: String,
                                     activity: String,
                                     replyTo: ActorRef[StatusReply[DeliveryAttemptRecorded.type]]) extends Cmd

    case object PayloadDeleted extends Cmd
    case object Stop extends Cmd
  }

  trait Event   //all events would be defined in message-events.proto file

  trait State
  object States {
    case object Uninitialized extends State
    case class Initialized(msg: Msg, deliveryStatus: Map[OutboxId, OutboxDeliveryStatus] = Map.empty) extends State

    //Processed meaning the message has been processed by all outboxes
    // (either it would have been delivered or failed after exhausted all retry attempts)
    case class Processed(msg: Msg, deliveryStatus: Map[OutboxId, OutboxDeliveryStatus] = Map.empty) extends State
  }


  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Message")

  def apply(entityContext: EntityContext[Cmd],
            bucketName: String,
            storageAPI: StorageAPI): Behavior[Cmd] = {
    Behaviors.setup { context =>
      EventSourcedBehavior
        .withEnforcedReplies(
          PersistenceId(TypeKey.name, entityContext.entityId),
          States.Uninitialized,
          commandHandler(context, entityContext.entityId, bucketName, storageAPI),
          eventHandler)
        .receiveSignal(signalHandler(context, entityContext.entityId, bucketName, storageAPI))
        .eventAdapter(new EventPersistenceAdapter(entityContext.entityId, EventObjectMapper))
    }
  }

  private def commandHandler(context: ActorContext[Cmd],
                             msgId: MsgId,
                             bucketName: String,
                             storageAPI: StorageAPI): (State, Cmd) => ReplyEffect[Event, State] = {

    case (States.Uninitialized, Commands.Get(replyTo)) =>
      Effect.reply(replyTo)(StatusReply.success(MsgNotYetAdded))

    case (States.Uninitialized, c: Commands.Add) =>
      Effect
        .persist(Events.MsgAdded(c.`type`, c.legacyMsgData, c.retentionPolicy.configString, Option(c.payloadStorageInfo), c.outboxIds.toSeq))
        .thenReply(c.replyTo)(_ => StatusReply.success(MsgAdded))

    case (_: States.Initialized, c: Commands.Add) =>
      Effect.reply(c.replyTo)(StatusReply.success(MsgAlreadyAdded))

    case (st: States.Initialized, Commands.Get(replyTo)) =>
      Effect.reply(replyTo)(StatusReply.success(st.msg))

    case (st: States.Initialized, Commands.GetDeliveryStatus(replyTo)) =>
      Effect.reply(replyTo)(StatusReply.success(MsgDeliveryStatus(st.deliveryStatus)))

    case (_: States.Initialized, ads: Commands.RecordDeliveryAttempt) =>
      val deliveryActivity = ComMethodActivity(TimeZoneUtil.getMillisForCurrentUTCZonedDateTime, ads.comMethodId, ads.activity)
      val deliveryAttemptAdded = Events.DeliveryAttemptRecorded(ads.outboxId, ads.status, Option(deliveryActivity))
      Effect
        .persist(deliveryAttemptAdded)
        .thenRun((state: State) => deletePayloadIfRequired(context, msgId, bucketName, storageAPI, state))
        .thenReply(ads.replyTo)(_ => StatusReply.success(DeliveryAttemptRecorded))


    case (_: States.Initialized, Commands.PayloadDeleted) =>
      Effect.persist(Events.PayloadDeleted()).thenNoReply()

    case (st: States.Processed, Commands.GetDeliveryStatus(replyTo)) =>
      Effect.reply(replyTo)(StatusReply.success(MsgDeliveryStatus(st.deliveryStatus)))

    case (st: States.Processed, Commands.Get(replyTo)) =>
      Effect.reply(replyTo)(StatusReply.success(st.msg))

    case (_: State, Commands.Stop) =>
      Behaviors.stopped
      Effect.noReply
  }

  private val eventHandler: (State, Event) => State = {

    case (States.Uninitialized, Events.MsgAdded(typ, legacyMsgData, policy, Some(storageInfo), outboxIds))  =>
      val msg = Msg(typ, legacyMsgData.map(LegacyData(_)), ConfigUtil.getPolicyFromConfigStr(policy), storageInfo)
      val statusByOutboxes = outboxIds.map (id => id -> OutboxDeliveryStatus()).toMap
      States.Initialized(msg, statusByOutboxes)

    case (st: States.Initialized, dsa: Events.DeliveryAttemptRecorded) =>

      val updatedOutboxDeliveryStatus = {
        val newActivity = dsa.comMethodActivity.map(a =>
          Activity(a.comMethodId, a.detail, TimeZoneUtil.getUTCZonedDateTimeFromMillis(a.creationTimeInMillis)))
        val outboxDeliveryStatus = st.deliveryStatus.getOrElse(dsa.outboxId, OutboxDeliveryStatus())
        outboxDeliveryStatus.copy(status = dsa.status, activities = outboxDeliveryStatus.activities ++ newActivity)
      }

      val updatedDeliveryStatus = st.deliveryStatus ++ Map(dsa.outboxId -> updatedOutboxDeliveryStatus)

      st.copy(deliveryStatus = updatedDeliveryStatus)

    case (st: States.Initialized, _: Events.PayloadDeleted) => Processed(st.msg, st.deliveryStatus)
  }

  private def signalHandler(context: ActorContext[Cmd],
                            msgId: MsgId,
                            bucketName: String,
                            storageAPI: StorageAPI): PartialFunction[(State, Signal), Unit] = {
    case (st: States.Initialized, RecoveryCompleted) =>
      deletePayloadIfRequired(context, msgId, bucketName, storageAPI, st)
  }

  private def deletePayloadIfRequired(context: ActorContext[Cmd],
                                      msgId: MsgId,
                                      bucketName: String,
                                      storageAPI: StorageAPI,
                                      st: State): Unit = {
    st match {
      case i: Initialized =>
        //checks and deleted if payload stored in external location (s3 etc) needs to be deleted
        val terminalStatusCode = List(Status.MSG_DELIVERY_STATUS_SENT, Status.MSG_DELIVERY_STATUS_FAILED).map(_.statusCode)
        if (i.deliveryStatus.forall(ds => terminalStatusCode.contains(ds._2.status))) {
          lazy val msgIdLifeCycleAddress = BucketLifeCycleUtil.lifeCycleAddress(
            Option(i.msg.policy.elements.expiryDaysStr), msgId)
          val fut = storageAPI.delete(bucketName, msgIdLifeCycleAddress)
          context.pipeToSelf(fut) {
            case Success(_)         => PayloadDeleted
            case Failure(exception) => throw exception
          }
        }
      case _ => //nothing to do
    }
  }
}

