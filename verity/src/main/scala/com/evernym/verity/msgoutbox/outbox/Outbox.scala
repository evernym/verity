package com.evernym.verity.msgoutbox.outbox

import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.{DeleteEventsCompleted, DeleteEventsFailed, DeleteSnapshotsFailed, PersistenceId, RecoveryCompleted, SnapshotCompleted, SnapshotFailed}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.msgoutbox._
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.MsgActivity
import com.evernym.verity.msgoutbox.outbox.Events.{MsgSendingFailed, MsgSentSuccessfully, OutboxParamUpdated}
import com.evernym.verity.msgoutbox.outbox.Outbox.Cmd
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.{GetOutboxParam, ProcessDelivery, RelResolverReplyAdapter}
import com.evernym.verity.msgoutbox.outbox.States.{Message, MsgDeliveryAttempt}
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.msg_packager.Packagers
import com.evernym.verity.msgoutbox.outbox.msg_transporter.Transports
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.actor.typed.base.{PersistentEventAdapter, PersistentStateAdapter}
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.TimeZoneUtil
import com.evernym.verity.util2.Status
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import java.time.ZonedDateTime
import scala.concurrent.duration._

//persistent entity, holds undelivered messages
// orchestrates the delivery flow (which com method to use, retry policy etc)
// cleaning up of delivered messages
object Outbox {

  //commands
  trait Cmd extends ActorMessage
  object Commands {
    case class UpdateOutboxParam(walletId: String, senderVerKey: VerKey, comMethods: Map[ComMethodId, ComMethod]) extends Cmd
    case class GetOutboxParam(replyTo: ActorRef[StatusReply[RelationshipResolver.Replies.OutboxParam]]) extends Cmd
    case class GetDeliveryStatus(replyTo: ActorRef[StatusReply[Replies.DeliveryStatus]]) extends Cmd
    case class AddMsg(msgId: MsgId, replyTo: ActorRef[StatusReply[Replies.MsgAddedReply]]) extends Cmd

    case class RecordSuccessfulAttempt(msgId: MsgId,
                                       comMethodId: String,
                                       sendAck: Boolean,
                                       isItANotification: Boolean) extends Cmd

    case class RecordFailedAttempt(msgId: MsgId,
                                   comMethodId: String,
                                   sendAck: Boolean,
                                   isItANotification: Boolean,
                                   isAnyRetryAttemptsLeft: Boolean,
                                   statusDetail: StatusDetail) extends Cmd
    case class RemoveMsg(msgId:MsgId, deliveryStatus: String) extends Cmd

    case class RelResolverReplyAdapter(reply: RelationshipResolver.Reply) extends Cmd

    case object ProcessDelivery extends Cmd     //sent by scheduled job
    case object TimedOut extends Cmd
  }

  trait Reply extends ActorMessage
  object Replies {
    trait MsgAddedReply extends Reply
    case object MsgAlreadyAdded extends MsgAddedReply
    case object MsgAdded extends MsgAddedReply
    case class DeliveryStatus(messages: Map[MsgId, Message]) extends Reply
  }

  trait Event   //all events would be defined in outbox-events.proto file
  trait State   //all states would be defined in outbox-states.proto file (because of snapshotting purposes)

  trait MessageBase {
    def creationTimeInMillis: Long
    val creationTime: ZonedDateTime = TimeZoneUtil.getUTCZonedDateTimeFromMillis(creationTimeInMillis)
  }

  //behavior
  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Outbox")

  def apply(entityContext: EntityContext[Cmd],
            config: Config,
            relResolver: Behavior[RelationshipResolver.Cmd],
            msgStore: ActorRef[MsgStore.Cmd],
            packagers: Packagers,
            transports: Transports): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      Behaviors.withTimers { timer =>
        timer.startTimerWithFixedDelay("process-delivery", ProcessDelivery, scheduledJobInterval(config))
        Behaviors.withStash(100) { buffer =>                     //TODO: finalize this
          actorContext.setReceiveTimeout(receiveTimeout(config), Commands.TimedOut)
          val relResolverReplyAdapter = actorContext.messageAdapter(reply => RelResolverReplyAdapter(reply))

          val dispatcher = new Dispatcher(
            actorContext,
            config,
            msgStore,
            packagers,
            transports
          )
          val setup = SetupOutbox(actorContext,
            entityContext,
            config,
            buffer,
            relResolver,
            relResolverReplyAdapter,
            dispatcher
          )
          EventSourcedBehavior
            .withEnforcedReplies(
              PersistenceId(TypeKey.name, entityContext.entityId),
              States.Uninitialized.apply(), //TODO: wasn't able to just use 'States.Uninitialized'
              commandHandler(setup),
              eventHandler(dispatcher))
            .receiveSignal(signalHandler(setup))
            .eventAdapter(PersistentEventAdapter(entityContext.entityId, EventObjectMapper))
            .snapshotAdapter(PersistentStateAdapter(entityContext.entityId, StateObjectMapper))
            .withRetention(retentionCriteria(config))
        }
      }
    }
  }

  private def commandHandler(implicit setup: SetupOutbox): (State, Cmd) => ReplyEffect[Event, State] = {

    //during initialization
    case (_: States.Uninitialized, RelResolverReplyAdapter(reply: RelationshipResolver.Replies.OutboxParam)) =>
      Effect
        .persist(OutboxParamUpdated(reply.walletId, reply.senderVerKey, reply.comMethods))
        .thenRun((_: State) => setup.buffer.unstashAll(Behaviors.same))
        .thenNoReply()

    case (_: States.Uninitialized, cmd) =>
      setup.buffer.stash(cmd)
      Effect
        .noReply


      //post initialization
    case (st: States.Initialized, RelResolverReplyAdapter(reply: RelationshipResolver.Replies.OutboxParam)) =>

      if (st.senderVerKey != reply.senderVerKey || st.comMethods != reply.comMethods) {
        Effect
          .persist(OutboxParamUpdated(reply.walletId, reply.senderVerKey, reply.comMethods))
          .thenNoReply()
      } else {
        Effect
          .noReply
      }

    case (st: States.Initialized, GetOutboxParam(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(
          RelationshipResolver.Replies.OutboxParam(st.walletId, st.senderVerKey, st.comMethods))
        )

    case (st: States.Initialized, Commands.AddMsg(msgId, replyTo)) =>
      if (st.messages.contains(msgId)) {
        Effect
          .reply(replyTo)(StatusReply.success(Replies.MsgAlreadyAdded))
      } else {
        Effect
          .persist(Events.MsgAdded(TimeZoneUtil.getMillisForCurrentUTCZonedDateTime, msgId))
          .thenRun((st: State) => processPendingDeliveries(st))
          .thenReply(replyTo)((_: State) => StatusReply.success(Replies.MsgAdded))
      }

    case (st: States.Initialized, Commands.GetDeliveryStatus(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(Replies.DeliveryStatus(st.messages)))

    case (st: States.Initialized, Commands.ProcessDelivery) =>
      processDelivery(st)
      Effect
        .noReply

    case (st: States.Initialized, rsa: Commands.RecordSuccessfulAttempt) =>
      val isDelivered = isMsgDelivered(rsa.comMethodId, rsa.isItANotification, st)
      Effect
        .persist(MsgSentSuccessfully(rsa.msgId, rsa.comMethodId, isDelivered))
        .thenRun((_: State) => if (rsa.sendAck) setup.dispatcher.ack(rsa.msgId))
        .thenRun((st: State) => sendMsgActivityToMessageMeta(st, rsa.msgId, rsa.comMethodId, None))
        .thenNoReply()

    case (st: States.Initialized, rfa: Commands.RecordFailedAttempt) =>
      val isDeliveryFailed = isMsgDeliveryFailed(rfa.comMethodId,
        rfa.isItANotification, rfa.isAnyRetryAttemptsLeft, st)(setup.config)
      Effect
        .persist(MsgSendingFailed(rfa.msgId, rfa.comMethodId, isDeliveryFailed))
        .thenRun((_: State) => if (rfa.sendAck) setup.dispatcher.ack(rfa.msgId))
        .thenRun((st: State) => sendMsgActivityToMessageMeta(st, rfa.msgId, rfa.comMethodId, Option(rfa.statusDetail)))
        .thenNoReply()

    case (st: States.Initialized, Commands.RemoveMsg(msgId, deliveryStatus)) =>
      if (st.messages.contains(msgId)) {
        Effect
          .persist(Events.MsgRemoved(msgId))
          .thenRun((_: State) => sendMsgRemovedToMessageMeta(
            msgId, deliveryStatus, Option(MsgActivity("removed from outbox: " + setup.entityContext.entityId))))
          .thenNoReply()
      } else Effect.noReply

    case (_: State, Commands.TimedOut) =>
      Effect
        .stop()
        .thenNoReply()
  }

  private def eventHandler(dispatcher: Dispatcher): (State, Event) => State = {
    case (_, opu @ OutboxParamUpdated(walletId, senderVerKey, comMethods)) =>
      dispatcher.updateDispatcher(opu.walletId, opu.senderVerKey, opu.comMethods)
      States.Initialized(walletId, senderVerKey, comMethods)

    case (st: States.Initialized, ma: Events.MsgAdded) =>
      val msg = Message(ma.creationTimeInMillis,Status.MSG_DELIVERY_STATUS_PENDING.statusCode, Map.empty)
      st.copy(messages = st.messages ++ Map(ma.msgId -> msg))

    case (st: States.Initialized, mss: MsgSentSuccessfully) =>
      val message =
        st.messages.getOrElse(mss.msgId,
          throw new RuntimeException("message not found with id: " + mss.msgId))

      val updatedDeliveryStatus =
        if (mss.isDelivered) Status.MSG_DELIVERY_STATUS_SENT.statusCode
        else message.deliveryStatus

      val updatedDeliveryAttempt = {
        val deliveryAttempt = message.deliveryAttempts.getOrElse(mss.comMethodId, MsgDeliveryAttempt())
        deliveryAttempt.copy(successCount = deliveryAttempt.successCount + 1)
      }

      val updatedMessage = message.copy(
        deliveryStatus = updatedDeliveryStatus,
        deliveryAttempts = message.deliveryAttempts ++ Map(mss.comMethodId -> updatedDeliveryAttempt)
      )

      st.copy(messages = st.messages + (mss.msgId -> updatedMessage))

    case (st: States.Initialized, mss: MsgSendingFailed) =>
      val message =
        st.messages.getOrElse(mss.msgId,
          throw new RuntimeException("message not found with id: " + mss.msgId))

      val updatedDeliveryStatus =
        if (mss.isDeliveryFailed) Status.MSG_DELIVERY_STATUS_FAILED.statusCode
        else message.deliveryStatus

      val updatedDeliveryAttempt = {
        val deliveryAttempt = message.deliveryAttempts.getOrElse(mss.comMethodId, MsgDeliveryAttempt())
        deliveryAttempt.copy(failedCount = deliveryAttempt.failedCount + 1)
      }

      val updatedMessage = message.copy(
        deliveryStatus = updatedDeliveryStatus,
        deliveryAttempts = message.deliveryAttempts ++ Map(mss.comMethodId -> updatedDeliveryAttempt)
      )

      st.copy(messages = st.messages + (mss.msgId -> updatedMessage))

    case (st: States.Initialized, Events.MsgRemoved(msgId)) =>
      st.copy(messages = st.messages - msgId)
  }

  private def signalHandler(implicit setup: SetupOutbox): PartialFunction[(State, Signal), Unit] = {
    case (st: State, RecoveryCompleted) =>
      updateDispatcherIfRequired(setup.dispatcher, st)
      val outboxIdParam = OutboxIdParam(setup.entityContext.entityId)
      fetchOutboxParam(outboxIdParam)

    case (_: States.Initialized, sc: SnapshotCompleted) =>
      logger.info(s"[${setup.entityContext.entityId}] snapshot completed: " + sc)
    case (_, sf: SnapshotFailed) =>
      logger.error(s"[${setup.entityContext.entityId}] snapshot failed with error: " + sf.failure.getMessage)
    case (_, dsf: DeleteSnapshotsFailed) =>
      logger.error(s"[${setup.entityContext.entityId}] delete snapshot failed with error: " + dsf.failure.getMessage)

    case (_, dc: DeleteEventsCompleted) =>
      logger.info(s"[${setup.entityContext.entityId}] delete events completed: " + dc)
    case (_, df: DeleteEventsFailed) =>
      logger.info(s"[${setup.entityContext.entityId}] delete events failed with error: " + df.failure.getMessage)
  }

  private def updateDispatcherIfRequired(dispatcher: Dispatcher, state: State): Unit = {
    state match {
      case i: States.Initialized => dispatcher.updateDispatcher(i.walletId, i.senderVerKey, i.comMethods)
      case _ => //nothing to do
    }
  }

  //after a successful attempt, decides if the message can be considered as delivered
  private def isMsgDelivered(comMethodId: String,
                             isItANotification: Boolean,
                             st: States.Initialized): Boolean = {
    //TODO: check/confirm this logic

    if (isItANotification) false    //successful notification doesn't mean message is delivered
    else {
      val comMethodOpt = st.comMethods.get(comMethodId)
      comMethodOpt match {
        case None =>
          // NOTE: it may happen that com methods got updated (while delivery was in progress) in such a way
          // that there is no com method with 'comMethodId' hence below logic handles it instead of failing
          true    //assumption is that this must be either webhook or websocket
        case Some(cm) =>
          //for "http" or "websocket" com method type, the successful message sending can be considered as delivered
          cm.typ == COM_METHOD_TYPE_HTTP_ENDPOINT
      }
    }
  }

  //after a failed attempt, decides if the message can be considered as failed (permanently)
  private def isMsgDeliveryFailed(comMethodId: String,
                                  isItANotification: Boolean,
                                  isAnyRetryAttemptLeft: Boolean,
                                  st: States.Initialized)
                                 (implicit config: Config): Boolean = {
    //TODO: check/confirm this logic
    if (isItANotification) false    //failed notification doesn't mean message delivery is failed
    else {
      val comMethodOpt = st.comMethods.get(comMethodId)
      comMethodOpt match {
        case None =>
          // NOTE: it may happen that com methods got updated (while delivery was in progress) in such a way
          // that there is no com method with 'comMethodId' hence below logic handles it instead of failing
          true
        case Some(cm) =>
          cm.typ == COM_METHOD_TYPE_HTTP_ENDPOINT && ! isAnyRetryAttemptLeft
      }
    }
  }

  private def sendMsgActivityToMessageMeta(state: State,
                                           msgId: MsgId,
                                           comMethodId: String,
                                           statusDetail: Option[StatusDetail])
                                          (implicit setup: SetupOutbox): Unit = {
    state match {
      case i: States.Initialized =>
        val msg = i.messages(msgId)
        val deliveryAttempt = msg.deliveryAttempts(comMethodId)
        val activityDetail =
          s"comMethodId: $comMethodId, " +
          s"successCount: ${deliveryAttempt.successCount}, " +
            s"failedCount: ${deliveryAttempt.failedCount}" +
            statusDetail.map(sd => s", statusDetail => code: ${sd.statusCode}, msg: ${sd.statusMsg}").getOrElse("")
        val msgActivity = Option(MsgActivity(activityDetail))
        val cmd = MessageMeta.Commands.RecordMsgActivity(
          setup.entityContext.entityId,
          msg.deliveryStatus,
          msgActivity
        )
        val entityRef = ClusterSharding(setup.actorContext.system).entityRefFor(MessageMeta.TypeKey, msgId)
        entityRef ! cmd
      case _ => //nothing to do
    }
  }

  private def sendMsgRemovedToMessageMeta(msgId: MsgId,
                                          deliveryStatus: String,
                                          msgActivity: Option[MsgActivity])
                                         (implicit setup: SetupOutbox): Unit = {
    val cmd = MessageMeta.Commands.MsgRemovedFromOutbox(
      setup.entityContext.entityId,
      deliveryStatus,
      msgActivity
    )
    val entityRef = ClusterSharding(setup.actorContext.system).entityRefFor(MessageMeta.TypeKey, msgId)
    entityRef ! cmd
  }

  private def processDelivery(st: States.Initialized)(implicit setup: SetupOutbox): Unit = {
    //remove processed messages(either delivered or permanently failed) in case left uncleaned
    removeProcessedMsgs(st)

    //process pending webhook deliveries
    processPendingDeliveries(st)
  }

  private def removeProcessedMsgs(st: State)(implicit setup: SetupOutbox): Unit = {
    //TODO: any batching here?
    st match {
      case i: States.Initialized =>
        i.messages
          .filter(m => processedMsgStatusCodes.contains(m._2.deliveryStatus))
          .foreach { case (msgId, msg) =>
            setup.actorContext.self ! Commands.RemoveMsg(msgId, msg.deliveryStatus)
          }
      case _ =>
    }
  }

  private def processPendingDeliveries(st: State)(implicit setup: SetupOutbox): Unit = {
    st match {
      case i: States.Initialized =>
        i.messages
          .filter(_._2.deliveryStatus == Status.MSG_DELIVERY_STATUS_PENDING.statusCode)
          .toSeq
          .sortBy(_._2.creationTimeInMillis)    //TODO: any issue with sorting here?
          .take(batchSize(setup.config))
          .foreach{ case (msgId, _) => sendToDispatcher(msgId, i)}
      case _ => //nothing to do
    }
  }

  private def fetchOutboxParam(outboxIdParam: OutboxIdParam)
                              (implicit setup: SetupOutbox): Unit =  {
    val relResolverRef = setup.actorContext.spawnAnonymous(setup.relResolver)
    relResolverRef ! RelationshipResolver.Commands.SendOutboxParam(
      outboxIdParam.relId, outboxIdParam.destId, setup.relResolverReplyAdapter)
  }

  val logger: Logger = getLoggerByClass(getClass)

  private def batchSize(config: Config): Int = {
    ConfigReadHelper(config)
      .getIntOption("verity.outbox.batch-size")
      .getOrElse(50)
  }

  private def receiveTimeout(config: Config): FiniteDuration = {
    //TODO: finalize this
    ConfigReadHelper(config)
      .getDurationOption("verity.outbox.receive-timeout")
      .getOrElse(FiniteDuration(600, SECONDS))
  }

  private def scheduledJobInterval(config: Config): FiniteDuration = {
    //TODO: finalize this
    ConfigReadHelper(config)
      .getDurationOption("verity.outbox.scheduled-job-interval")
      .getOrElse(FiniteDuration(5, MILLISECONDS))
  }

  private def retentionCriteria(config: Config): RetentionCriteria = {
    //TODO: finalize this
    val afterEveryEvents =
      ConfigReadHelper(config)
        .getIntOption("verity.outbox.retention-criteria.snapshot.after-every-events")
        .getOrElse(100)
    val keepSnapshots =
      ConfigReadHelper(config)
        .getIntOption("verity.outbox.retention-criteria.snapshot.keep-snapshots")
        .getOrElse(2)
    val deleteEventOnSnapshot =
      ConfigReadHelper(config)
        .getBooleanOption("verity.outbox.retention-criteria.snapshot.delete-events-on-snapshots")
        .getOrElse(true)
    val retentionCriteria =
      RetentionCriteria.snapshotEvery(numberOfEvents = afterEveryEvents, keepNSnapshots = keepSnapshots)
    if (deleteEventOnSnapshot) retentionCriteria.withDeleteEventsOnSnapshot
    else retentionCriteria
  }

  //TODO: finalize this (idea is to have one dispatcher based on com method as part of state)
  private def sendToDispatcher(msgId: MsgId,
                               state: States.Initialized)
                              (implicit setup: SetupOutbox): Unit = {
    val msg = state.messages(msgId)
    setup.dispatcher.dispatch(msgId, msg.deliveryAttempts)
  }

  val processedMsgStatusCodes: Seq[String] = List(
    Status.MSG_DELIVERY_STATUS_SENT,
    Status.MSG_DELIVERY_STATUS_FAILED
  ).map(_.statusCode)
}

object OutboxIdParam {
  def apply(entityId: String): OutboxIdParam = {
    val tokens = entityId.split("-", 3)
    if (tokens.size != 3) {
      Behaviors.stopped
      throw new RuntimeException("invalid outbox id: " + entityId)
    }
    OutboxIdParam(tokens(0), tokens(1), tokens(2))
  }
}

case class OutboxIdParam(relId: RelId, recipId: RecipId, destId: DestId) {
  val outboxId: OutboxId = relId + "-" + recipId + "-" + destId
}

case class SetupOutbox(actorContext: ActorContext[Cmd],
                       entityContext: EntityContext[Cmd],
                       config: Config,
                       buffer: StashBuffer[Cmd],
                       relResolver: Behavior[RelationshipResolver.Cmd],
                       relResolverReplyAdapter: ActorRef[RelationshipResolver.Reply],
                       dispatcher: Dispatcher)