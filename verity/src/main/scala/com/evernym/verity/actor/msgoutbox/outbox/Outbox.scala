package com.evernym.verity.actor.msgoutbox.outbox

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.{DeleteEventsCompleted, DeleteEventsFailed, DeleteSnapshotsFailed, PersistenceId, RecoveryCompleted, SnapshotCompleted, SnapshotFailed}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.evernym.verity.Status
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.msgoutbox.ComMethod
import com.evernym.verity.actor.msgoutbox.adapters.RelationshipResolver.Commands.SendOutboxParam
import com.evernym.verity.actor.msgoutbox.adapters.RelationshipResolver.Replies.OutboxParam
import com.evernym.verity.actor.msgoutbox.adapters.{MsgStore, RelationshipResolver, Transports, WalletOpExecutor}
import com.evernym.verity.actor.msgoutbox.message.MessageMeta
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.ComMethodActivity
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.Commands.RecordDeliveryStatus
import com.evernym.verity.actor.msgoutbox.outbox.Events.{MsgSendingFailed, MsgSentSuccessfully, OutboxParamUpdated}
import com.evernym.verity.actor.msgoutbox.outbox.Outbox.Commands.{GetOutboxParam, ProcessDelivery, RelResolverReplyAdapter}
import com.evernym.verity.actor.msgoutbox.outbox.States.{Message, MsgDeliveryAttempt}
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.base.MessageDispatcher
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.base.MessageDispatcher.Commands.DeliverMsg
import com.evernym.verity.actor.typed.base.EventPersistenceAdapter
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.TimeZoneUtil
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
    case class GetOutboxParam(replyTo: ActorRef[StatusReply[RelationshipResolver.Replies.OutboxParam]]) extends Cmd
    case class UpdateOutboxParam(walletId: String, senderVerKey: VerKey, comMethods: Map[String, ComMethod]) extends Cmd
    case class AddMsg(msgId: MsgId, replyTo: ActorRef[StatusReply[Replies.MsgAddedReply]]) extends Cmd
    case class GetDeliveryStatus(replyTo: ActorRef[StatusReply[Replies.DeliveryStatus]]) extends Cmd

    case class RecordSuccessfulAttempt(msgId: MsgId, comMethodId: String, isItANotification: Boolean) extends Cmd
    case class RecordFailedAttempt(msgId: MsgId,
                                   comMethodId: String,
                                   isItANotification: Boolean,
                                   isAnyRetryAttemptsLeft: Boolean,
                                   statusDetail: StatusDetail) extends Cmd
    case class RemoveMsg(msg:MsgId) extends Cmd

    case class RelResolverReplyAdapter(reply: RelationshipResolver.Reply) extends Cmd

    case object ProcessDelivery extends Cmd     //sent by scheduled job
    case object Stop extends Cmd
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
            msgStore: ActorRef[MsgStore.Cmd],
            relResolver: Behavior[RelationshipResolver.Cmd],
            walletOpExecutor: Behavior[WalletOpExecutor.Cmd],
            transports: Transports): Behavior[Cmd] = {
    Behaviors.withTimers { timer =>
      timer.startTimerWithFixedDelay("process-delivery", ProcessDelivery, scheduledJobInterval(config))
      Behaviors.withStash(10) { buffer =>                     //TODO: finalize this
        Behaviors.setup { actorContext =>
          val relResolverReplyAdapter = actorContext.messageAdapter(reply => RelResolverReplyAdapter(reply))
          actorContext.setReceiveTimeout(receiveTimeout(config), Commands.Stop)
          EventSourcedBehavior
            .withEnforcedReplies(
              PersistenceId(TypeKey.name, entityContext.entityId),
              States.Uninitialized.apply(), //TODO: wasn't able to just use 'States.Uninitialized'
              commandHandler(msgStore, walletOpExecutor, transports)
                (config, buffer, entityContext, actorContext),
              eventHandler)
            .receiveSignal(signalHandler(relResolver)(entityContext, actorContext, relResolverReplyAdapter))
            .eventAdapter(new EventPersistenceAdapter(entityContext.entityId, EventObjectMapper))
            .withRetention(retentionCriteria(config))
        }
      }
    }
  }

  private def commandHandler(msgStore: ActorRef[MsgStore.Cmd],
                             walletOpExecutor: Behavior[WalletOpExecutor.Cmd],
                             transports: Transports)
                            (implicit config: Config,
                             buffer: StashBuffer[Cmd],
                             entityContext: EntityContext[Cmd],
                             actorContext: ActorContext[Cmd]): (State, Cmd) => ReplyEffect[Event, State] = {

    //during initialization
    case (_: States.Uninitialized, RelResolverReplyAdapter(reply: OutboxParam)) =>
      Effect
        .persist(OutboxParamUpdated(reply.walletId, reply.senderVerKey, reply.comMethods))
        .thenRun((_: State) => buffer.unstashAll(Behaviors.same))
        .thenNoReply()

    case (_: States.Uninitialized, cmd) =>
      buffer.stash(cmd)
      Effect
        .noReply


      //post initialization
    case (st: States.Initialized, RelResolverReplyAdapter(reply: OutboxParam)) =>
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
          .thenRun((st: State) => processDelivery(st, msgStore, walletOpExecutor, transports))
          .thenReply(replyTo)((_: State) => StatusReply.success(Replies.MsgAdded))
      }

    case (st: States.Initialized, Commands.GetDeliveryStatus(replyTo)) =>
      Effect
        .reply(replyTo)(StatusReply.success(Replies.DeliveryStatus(st.messages)))

    case (st: States.Initialized, Commands.ProcessDelivery) =>
      processDelivery(st, msgStore, walletOpExecutor, transports)
      Effect
        .noReply

    case (st: States.Initialized, rsa: Commands.RecordSuccessfulAttempt) =>
      val isDelivered = isMsgDelivered(rsa.comMethodId, rsa.isItANotification, st)
      Effect
        .persist(MsgSentSuccessfully(rsa.msgId, rsa.comMethodId, isDelivered))
        .thenRun((st: State) => sendDeliveryStatusToMsgActor(st, rsa.msgId, rsa.comMethodId, None))
        .thenRun((_: State)  => if (isDelivered) actorContext.self ! Commands.RemoveMsg(rsa.msgId))
        .thenNoReply()

    case (st: States.Initialized, rfa: Commands.RecordFailedAttempt) =>
      val isDeliveryFailed = isMsgDeliveryFailed(rfa.msgId, rfa.comMethodId,
        rfa.isItANotification, rfa.isAnyRetryAttemptsLeft, st)
      Effect
        .persist(MsgSendingFailed(rfa.msgId, rfa.comMethodId, isDeliveryFailed))
        .thenRun((st: State) => sendDeliveryStatusToMsgActor(st, rfa.msgId, rfa.comMethodId, Option(rfa.statusDetail)))
        .thenRun((_: State)  => if (isDeliveryFailed) actorContext.self ! Commands.RemoveMsg(rfa.msgId))
        .thenNoReply()

    case (_: States.Initialized, Commands.RemoveMsg(msgId)) =>
      Effect
        .persist(Events.MsgRemoved(msgId))
        .thenNoReply()

    case (_: State, Commands.Stop) =>
      Effect
        .stop()
        .thenNoReply()
  }

  private val eventHandler: (State, Event) => State = {
    case (_: States.Uninitialized, OutboxParamUpdated(walletId, senderVerKey, comMethods)) =>
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

  private def signalHandler(relResolver: Behavior[RelationshipResolver.Cmd])
                           (implicit entityContext: EntityContext[Cmd],
                            actorContext: ActorContext[Cmd],
                            relResolverReplyAdapter: ActorRef[RelationshipResolver.Reply])
  : PartialFunction[(State, Signal), Unit] = {
    case (_, RecoveryCompleted) =>
      val outboxIdParam = OutboxIdParam(entityContext.entityId)
      fetchOutboxParam(outboxIdParam, relResolver)

    case (_, _: SnapshotCompleted) =>
      logger.info(s"[${entityContext.entityId}] snapshot completed")
    case (_, sf: SnapshotFailed) =>
      logger.error(s"[${entityContext.entityId}] snapshot failed with error: " + sf.failure.getMessage)
    case (_, dsf: DeleteSnapshotsFailed) =>
      logger.error(s"[${entityContext.entityId}] delete snapshot failed with error: " + dsf.failure.getMessage)

    case (_, _: DeleteEventsCompleted) =>
      logger.info(s"[${entityContext.entityId}] delete events completed")
    case (_, df: DeleteEventsFailed) =>
      logger.info(s"[${entityContext.entityId}] delete events failed with error: " + df.failure.getMessage)

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
  private def isMsgDeliveryFailed(msgId: MsgId,
                                  comMethodId: String,
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
          val failedAttempts = st.messages(msgId).deliveryAttempts.get(comMethodId).map(_.failedCount).getOrElse(0) + 1
          cm.typ == COM_METHOD_TYPE_HTTP_ENDPOINT && ! isAnyRetryAttemptLeft
      }
    }
  }

  private def sendDeliveryStatusToMsgActor(state: State,
                                           msgId: MsgId,
                                           comMethodId: String,
                                           statusDetail: Option[StatusDetail])
                                          (implicit entityContext: EntityContext[Cmd],
                                           actorContext: ActorContext[Cmd]): Unit = {
    state match {
      case i: States.Initialized =>
        val msg = i.messages(msgId)
        val deliveryAttempt = msg.deliveryAttempts(comMethodId)
        val activityDetail =
          s"successCount: ${deliveryAttempt.successCount}, " +
            s"failedCount: ${deliveryAttempt.failedCount}" +
            statusDetail.map(sd => s", statusDetail => code: ${sd.statusCode}, msg: ${sd.statusMsg}").getOrElse("")
        val comMethodActivity = ComMethodActivity(comMethodId, activityDetail)
        sendDeliveryStatusToMsgActor(msgId, msg.deliveryStatus, Option(comMethodActivity))
      case _ => //nothing to do
    }
  }

  private def sendDeliveryStatusToMsgActor(msgId: MsgId,
                                           deliveryStatus: String,
                                           comMethodActivity: Option[ComMethodActivity])
                                          (implicit entityContext: EntityContext[Cmd],
                                           actorContext: ActorContext[Cmd]): Unit = {
    val cmd = RecordDeliveryStatus(
        entityContext.entityId,
        deliveryStatus,
        comMethodActivity
      )
    val entityRef = ClusterSharding(actorContext.system).entityRefFor(MessageMeta.TypeKey, msgId)
    entityRef ! cmd
  }

  private def processDelivery(st: State,
                              msgStore: ActorRef[MsgStore.Cmd],
                              walletOpExecutor: Behavior[WalletOpExecutor.Cmd],
                              transports: Transports)
                             (implicit config: Config,
                              entityContext: EntityContext[Cmd],
                              actorContext: ActorContext[Cmd]): Unit = {
    st match {
      case i: States.Initialized => processDelivery(i, msgStore, walletOpExecutor, transports)
      case _ => //nothing to do
    }
  }

  private def processDelivery(st: States.Initialized,
                              msgStore: ActorRef[MsgStore.Cmd],
                              walletOpExecutor: Behavior[WalletOpExecutor.Cmd],
                              transports: Transports)
                             (implicit config: Config,
                              entityContext: EntityContext[Cmd],
                              actorContext: ActorContext[Cmd]): Unit = {

    //remove processed messages(either delivered or permanently failed) in case left uncleaned
    removeProcessedMsgs(st)

    //process pending webhook deliveries
    processWebhookDeliveries(st, msgStore, walletOpExecutor, transports)
  }

  private def removeProcessedMsgs(st: States.Initialized)
                                (implicit entityContext: EntityContext[Cmd],
                                 actorContext: ActorContext[Cmd]): Unit = {
    val processedMessages = st.messages.filter(m =>
      m._2.deliveryStatus == Status.MSG_DELIVERY_STATUS_SENT.statusCode ||
        m._2.deliveryStatus == Status.MSG_DELIVERY_STATUS_FAILED.statusCode
    ).keySet
    processedMessages.foreach { msgId =>
      val msg = st.messages(msgId)
      sendDeliveryStatusToMsgActor(msgId, msg.deliveryStatus, None)
      actorContext.self ! Commands.RemoveMsg(msgId)
    }
  }

  private def processWebhookDeliveries(st: States.Initialized,
                                       msgStore: ActorRef[MsgStore.Cmd],
                                       walletOpExecutor: Behavior[WalletOpExecutor.Cmd],
                                       transports: Transports)
                                      (implicit config: Config,
                                       entityContext: EntityContext[Cmd],
                                       actorContext: ActorContext[Cmd]): Unit = {
    val pendingMessages = st.messages.filter(_._2.deliveryStatus == Status.MSG_DELIVERY_STATUS_PENDING.statusCode)
    val webhookMethod = st.comMethods.find(_._2.typ == COM_METHOD_TYPE_HTTP_ENDPOINT)
    pendingMessages.foreach { case (msgId, msg) =>
      webhookMethod.foreach { case (comMethodId, comMethod) =>
        val currFailedAttempt = msg.deliveryAttempts.get(comMethodId).map(_.failedCount).getOrElse(0)
        val retryParam = Option(prepareWebhookRetryParam(currFailedAttempt, config))
        sendToMessageDispatcher(msgId, comMethodId, comMethod, st.walletId, st.senderVerKey,
          retryParam, msgStore, walletOpExecutor, transports)
      }
    }
  }

  private def sendToMessageDispatcher(msgId: MsgId,
                                      comMethodId: String,
                                      comMethod: ComMethod,
                                      walletId: String,
                                      senderVerKey: VerKey,
                                      retryParam: Option[RetryParam],
                                      msgStore: ActorRef[MsgStore.Cmd],
                                      walletOpExecutor: Behavior[WalletOpExecutor.Cmd],
                                      transports: Transports)
                                     (implicit entityContext: EntityContext[Cmd],
                                      actorContext: ActorContext[Cmd]): Unit = {
    val msgProcessor = {
      actorContext.child(msgId).getOrElse(
        actorContext.spawn(
          MessageDispatcher(
            msgId,
            senderVerKey,
            walletId,
            walletOpExecutor,
            msgStore,
            transports
          ),
          msgId
        )
      )
    }
    //TODO: fix the .toClassic conversion
    msgProcessor.toClassic ! DeliverMsg(comMethodId, comMethod, retryParam, actorContext.self)
  }

  private def fetchOutboxParam(outboxIdParam: OutboxIdParam,
                               relResolver: Behavior[RelationshipResolver.Cmd])
                              (implicit actorContext: ActorContext[Cmd],
                               relResolverReplyAdapter: ActorRef[RelationshipResolver.Reply]): Unit =  {
    val relResolverRef = actorContext.spawnAnonymous(relResolver)
    relResolverRef ! SendOutboxParam(outboxIdParam.destId, relResolverReplyAdapter)
  }

  val logger: Logger = getLoggerByClass(getClass)


  def prepareWebhookRetryParam(failedAttemptCount: Int, config: Config): RetryParam = {
    //TODO: finalize this
    val maxRetries =
      ConfigReadHelper(config)
        .getIntOption("verity.outbox.webhook.retry-policy.max-retries")
        .getOrElse(5)

    val initialInterval =
      ConfigReadHelper(config)
        .getDurationOption("verity.outbox.webhook.retry-policy.initial-interval")
        .getOrElse(FiniteDuration(5, SECONDS))

    RetryParam(
      failedAttemptCount,
      maxRetries,
      initialInterval
    )
  }

  def receiveTimeout(config: Config): FiniteDuration = {
    //TODO: finalize this
    ConfigReadHelper(config)
      .getDurationOption("verity.outbox.receive-timeout")
      .getOrElse(FiniteDuration(600, SECONDS))
  }

  def scheduledJobInterval(config: Config): FiniteDuration = {
    //TODO: finalize this
    ConfigReadHelper(config)
      .getDurationOption("verity.outbox.scheduled-job-interval")
      .getOrElse(FiniteDuration(5, SECONDS))
  }

  def retentionCriteria(config: Config): RetentionCriteria = {
    //TODO: finalize this
    val numberOfEvents =
      ConfigReadHelper(config)
        .getIntOption("verity.outbox.retention-criteria.snapshot.after-number-of-events")
        .getOrElse(100)
    val keepNSnapshots =
      ConfigReadHelper(config)
        .getIntOption("verity.outbox.retention-criteria.snapshot.keep-n-snapshots")
        .getOrElse(2)
    val deleteEventOnSnapshot =
      ConfigReadHelper(config)
        .getBooleanOption("verity.outbox.retention-criteria.snapshot.delete-events-on-snapshots")
        .getOrElse(true)
    val retentionCriteria =
      RetentionCriteria.snapshotEvery(numberOfEvents = numberOfEvents, keepNSnapshots = keepNSnapshots)
    if (deleteEventOnSnapshot) retentionCriteria.withDeleteEventsOnSnapshot
    else retentionCriteria
  }
}

object OutboxIdParam {
  def apply(entityId: String): OutboxIdParam = {
    val tokens = entityId.split("-", 2)
    if (tokens.size != 2) {
      Behaviors.stopped
      throw new RuntimeException("invalid outbox id: " + entityId)
    }
    OutboxIdParam(tokens.head, tokens.last)
  }
}

case class OutboxIdParam(relId: RelId, destId: DestId) {
  val outboxId: OutboxId = relId + "-" + destId
}