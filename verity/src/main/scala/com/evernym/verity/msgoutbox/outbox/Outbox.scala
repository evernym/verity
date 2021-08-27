package com.evernym.verity.msgoutbox.outbox

import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityContext, EntityTypeKey}
import akka.persistence.typed.{DeleteEventsCompleted, DeleteEventsFailed, DeleteSnapshotsFailed, PersistenceId, RecoveryCompleted, SnapshotCompleted, SnapshotFailed}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.itemmanager.ItemManagerEntityHelper
import com.evernym.verity.msgoutbox._
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.MsgActivity
import com.evernym.verity.msgoutbox.outbox.Events.{MetadataStored, MsgSendingFailed, MsgSentSuccessfully, OutboxParamUpdated}
import com.evernym.verity.msgoutbox.outbox.Outbox.Cmd
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.{GetOutboxParam, MessageMetaReplyAdapter, ProcessDelivery, RelResolverReplyAdapter}
import com.evernym.verity.msgoutbox.outbox.States.{Message, Metadata, MsgDeliveryAttempt}
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.msg_packager.MsgPackagers
import com.evernym.verity.msgoutbox.outbox.msg_transporter.MsgTransports
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.actor.typed.base.{PersistentEventAdapter, PersistentStateAdapter}
import com.evernym.verity.config.ConfigConstants.{OUTBOX, OUTBOX_BATCH_SIZE, OUTBOX_OAUTH_RECEIVE_TIMEOUT, OUTBOX_RECEIVE_TIMEOUT, OUTBOX_RETENTION_SNAPSHOT_AFTER_EVERY_EVENTS, OUTBOX_RETENTION_SNAPSHOT_DELETE_EVENTS_ON_SNAPSHOTS, OUTBOX_RETENTION_SNAPSHOT_KEEP_SNAPSHOTS, OUTBOX_SCHEDULED_JOB_INTERVAL, SALT_EVENT_ENCRYPTION}
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.observability.metrics.CustomMetrics.{AS_OUTBOX_MSG_DELIVERY_FAILED_COUNT, AS_OUTBOX_MSG_DELIVERY_PENDING_COUNT, AS_OUTBOX_MSG_DELIVERY_SUCCESSFUL_COUNT}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.RetryParam
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.AccessTokenRefreshers
import com.evernym.verity.observability.metrics.{MetricsWriter, MetricsWriterExtension}
import com.evernym.verity.util.TimeZoneUtil
import com.evernym.verity.util2.Status
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import java.time.ZonedDateTime
import java.util.UUID

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//persistent entity, holds undelivered messages
// orchestrates the delivery flow (which com method to use, retry policy etc)
// cleaning up of delivered messages
object Outbox {

  //commands
  trait Cmd extends ActorMessage
  object Commands {
    case class UpdateOutboxParam(walletId: String, senderVerKey: VerKeyStr, comMethods: Map[ComMethodId, ComMethod]) extends Cmd
    case class GetOutboxParam(replyTo: ActorRef[RelationshipResolver.Replies.OutboxParam]) extends Cmd
    case class GetDeliveryStatus(replyTo: ActorRef[Replies.DeliveryStatus]) extends Cmd
    case class AddMsg(msgId: MsgId, expiryDuration: FiniteDuration, replyTo: ActorRef[Replies.MsgAddedReply]) extends Cmd
    case class Init(relId: RelId, recipId: RecipId, destId: DestId) extends Cmd

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

    case class RelResolverReplyAdapter(reply: RelationshipResolver.Reply) extends Cmd
    case class MessageMetaReplyAdapter(reply: MessageMeta.Reply) extends Cmd

    case class RemoveMsg(msgId: MsgId) extends Cmd
    case object ProcessDelivery extends Cmd     //sent by scheduled job
    case class UpdateConfig(config: OutboxConfig) extends Cmd
  }

  trait Reply extends ActorMessage
  object Replies {
    trait MsgAddedReply extends Reply
    case object MsgAlreadyAdded extends MsgAddedReply
    case object MsgAdded extends MsgAddedReply
    case class NotInitialized(entityId: String) extends MsgAddedReply
    case class DeliveryStatus(messages: Map[MsgId, Message]) extends Reply
  }

  trait Event   //all events would be defined in outbox-events.proto file
  trait State { //all states would be defined in outbox-states.proto file (because of snapshotting purposes)
    def config: OutboxConfig
  }

  trait MessageBase {
    def creationTimeInMillis: Long
    val creationTime: ZonedDateTime = TimeZoneUtil.getUTCZonedDateTimeFromMillis(creationTimeInMillis)
  }

  val DEFAULT_BATCH_SIZE: Int = 50
  val DEFAULT_RECEIVE_TIMEOUT: Long = 600000
  val DEFAULT_SCHEDULED_JOB_INTERVAL: Long = 5000
  val DEFAULT_RETENTION_SNAPSHOT_AFTER_EVERY_EVENTS: Int = 100
  val DEFAULT_RETENTION_SNAPSHOT_KEEP_SNAPSHOTS: Int = 2
  val DEFAULT_RETENTION_SNAPSHOT_DELETE_EVENTS_ON_SNAPSHOTS: Boolean = false
  val DEFAULT_OAUTH_RECEIVE_TIMEOUT: Long = 30000
  val DEFAULT_MAX_RETRIES: Int = 5
  val DEFAULT_INITIAL_INTERVAL: Long = 30000

  //behavior
  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Outbox")

  def apply(entityContext: EntityContext[Cmd],
            configuration: Config,
            accessTokenRefreshers: AccessTokenRefreshers,
            relResolver: Behavior[RelationshipResolver.Cmd],
            msgStore: ActorRef[MsgStore.Cmd],
            msgPackagers: MsgPackagers,
            msgTransports: MsgTransports,
            executionContext: ExecutionContext): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val config = prepareOutboxConfig(configuration)
      val eventEncryptionSalt = prepareEventEncryptionSalt(configuration)
      val retentionCriteria = prepareRetentionCriteria(configuration)
      Behaviors.withTimers { timer =>
        Behaviors.withStash(100) { buffer =>                     //TODO: finalize this
          val relResolverReplyAdapter = actorContext.messageAdapter(reply => RelResolverReplyAdapter(reply))
          val messageMetaReplyAdapter = actorContext.messageAdapter(reply => MessageMetaReplyAdapter(reply))
          val dispatcher = new Dispatcher(
            actorContext,
            accessTokenRefreshers,
            eventEncryptionSalt,
            msgStore,
            msgPackagers,
            msgTransports,
            executionContext
          )

          val setup = SetupOutbox(actorContext,
            entityContext,
            MetricsWriterExtension(actorContext.system).get(),
            buffer,
            dispatcher,
            relResolver,
            relResolverReplyAdapter,
            messageMetaReplyAdapter,
            new ItemManagerEntityHelper(entityContext.entityId, TypeKey.name, actorContext.system),
            timer
          )
          EventSourcedBehavior
            .withEnforcedReplies(
              PersistenceId(TypeKey.name, entityContext.entityId),
              States.Uninitialized(config),
              commandHandler(setup),
              eventHandler(dispatcher, timer))
            .receiveSignal(signalHandler(setup, config))
            .eventAdapter(PersistentEventAdapter(entityContext.entityId, EventObjectMapper, eventEncryptionSalt))
            .snapshotAdapter(PersistentStateAdapter(entityContext.entityId, StateObjectMapper, eventEncryptionSalt))
            .withRetention(retentionCriteria)
        }
      }
    }
  }

  private def commandHandler(setup: SetupOutbox)(st: State, cmd: Cmd): ReplyEffect[Event, State] = {
    st match {
      case st: States.Uninitialized => uninitializedStateHandler (setup)((st, cmd))
      case st: States.MetadataReceived =>  metadataReceivedStateHandler (setup)((st, cmd))
      case st: States.Initialized => initializedStateHandler (setup)((st, cmd))
      case _ => genericStateHandler()(st, cmd)
    }
  }

  private def genericStateHandler(): PartialFunction[(State, Cmd), ReplyEffect[Event, State]] = {
    case (_, Commands.UpdateConfig(cfg)) =>
      Effect
        .persist(Events.ConfigUpdated(cfg))
        .thenNoReply()
  }

  private def uninitializedStateHandler(implicit setup: SetupOutbox): PartialFunction[(States.Uninitialized, Cmd), ReplyEffect[Event, State]] = {
    case (_, cmd @ Commands.AddMsg(_, _, replyTo)) =>
      setup.buffer.stash(cmd)
      Effect
        .reply(replyTo)(Replies.NotInitialized(setup.entityContext.entityId))

    case (_, Commands.Init(relId, recipId, destId)) =>
      Effect
        .persist(MetadataStored(relId = relId, recipId = recipId, destId = destId))
        .thenRun((_: State) => fetchOutboxParam(Metadata(relId, recipId, destId)))
        .thenNoReply()

    case (_, cmd) =>
      setup.buffer.stash(cmd)
      Effect
        .noReply
  }

  private def metadataReceivedStateHandler(setup: SetupOutbox): PartialFunction[(States.MetadataReceived, Cmd), ReplyEffect[Event, State]] = {
    case (_, RelResolverReplyAdapter(reply: RelationshipResolver.Replies.OutboxParam)) =>
      Effect
        .persist(OutboxParamUpdated(reply.walletId, reply.senderVerKey, reply.comMethods))
        .thenRun((_: State) => setup.buffer.unstashAll(Behaviors.same))
        .thenNoReply()

    case (_, cmd) =>
      setup.buffer.stash(cmd)
      Effect
        .noReply
  }

  private def initializedStateHandler(implicit setup: SetupOutbox): PartialFunction[(States.Initialized, Cmd), ReplyEffect[Event, State]] = {
    messageHandlingCommandHandler orElse
      messageResendCommandHandler orElse
      timeoutCommandHandler orElse
      updateConfigCommandHandler orElse
      readCommandHandler
  }

  private def messageResendCommandHandler(implicit setup: SetupOutbox): PartialFunction[(States.Initialized, Cmd), ReplyEffect[Event, State]] = {
    case (st: States.Initialized, rsa: Commands.RecordSuccessfulAttempt) =>
      val isDelivered = isMsgDelivered(rsa.comMethodId, rsa.isItANotification, st)
      Effect
        .persist(MsgSentSuccessfully(rsa.msgId, rsa.comMethodId, isDelivered))
        .thenRun((_: State) => if (rsa.sendAck) setup.dispatcher.ack(rsa.msgId))
        .thenRun((_: State) => if (isDelivered && ! rsa.isItANotification) {
          setup.metricsWriter.gaugeIncrement(AS_OUTBOX_MSG_DELIVERY_SUCCESSFUL_COUNT, tags = comMethodTypeTags(st.comMethods.get(rsa.comMethodId)))
          setup.metricsWriter.gaugeDecrement(AS_OUTBOX_MSG_DELIVERY_PENDING_COUNT)
        })
        .thenRun((st: State) => sendMsgActivityToMessageMeta(st, rsa.msgId, rsa.comMethodId, None))
        .thenNoReply()

    case (st: States.Initialized, rfa: Commands.RecordFailedAttempt) =>
      val isDeliveryFailed = isMsgDeliveryFailed(rfa.comMethodId,
        rfa.isItANotification, rfa.isAnyRetryAttemptsLeft, st)
      Effect
        .persist(MsgSendingFailed(rfa.msgId, rfa.comMethodId, isDeliveryFailed))
        .thenRun((_: State) => setup.itemManagerEntityHelper.register())
        .thenRun((_: State) => if (rfa.sendAck) setup.dispatcher.ack(rfa.msgId))
        .thenRun((_: State) => if (isDeliveryFailed && ! rfa.isItANotification) {
          setup.metricsWriter.gaugeIncrement(AS_OUTBOX_MSG_DELIVERY_FAILED_COUNT, tags = comMethodTypeTags(st.comMethods.get(rfa.comMethodId)))
          setup.metricsWriter.gaugeDecrement(AS_OUTBOX_MSG_DELIVERY_PENDING_COUNT)
        })
        .thenRun((st: State) => sendMsgActivityToMessageMeta(st, rfa.msgId, rfa.comMethodId, Option(rfa.statusDetail)))
        .thenNoReply()
  }

  private def messageHandlingCommandHandler(implicit setup: SetupOutbox): PartialFunction[(States.Initialized, Cmd), ReplyEffect[Event, State]] = {
    case (st: States.Initialized, Commands.AddMsg(msgId, expiryDuration, replyTo)) =>
      if (st.messages.contains(msgId)) {
        Effect
          .reply(replyTo)(Replies.MsgAlreadyAdded)
      } else {
        Effect
          .persist(Events.MsgAdded(TimeZoneUtil.getMillisForCurrentUTCZonedDateTime, expiryDuration.toMillis, msgId))
          .thenRun((st: State) => processPendingDeliveries(st))
          .thenRun((_: State) => setup.metricsWriter.gaugeIncrement(AS_OUTBOX_MSG_DELIVERY_PENDING_COUNT))
          .thenReply(replyTo)((_: State) => Replies.MsgAdded)
      }

    case (st: States.Initialized, MessageMetaReplyAdapter(reply: MessageMeta.Replies.RemoveMsg)) =>
      if (st.messages.contains(reply.msgId)) {
        Effect
          .persist(Events.MsgRemoved(reply.msgId))
          .thenNoReply()
      } else Effect.noReply

    case (st: States.Initialized, Commands.RemoveMsg(msgId)) =>
      if (st.messages.contains(msgId)) {
        Effect
          .persist(Events.MsgRemoved(msgId))
          .thenNoReply()
      } else Effect.noReply
  }

  private def timeoutCommandHandler(implicit setup: SetupOutbox): PartialFunction[(States.Initialized, Cmd), ReplyEffect[Event, State]] = {
    case (st: States.Initialized, Commands.ProcessDelivery) =>
      processDelivery(st)
      Effect
        .noReply
  }

  private def updateConfigCommandHandler(implicit setup: SetupOutbox): PartialFunction[(States.Initialized, Cmd), ReplyEffect[Event, State]] = {
    case (st: States.Initialized, RelResolverReplyAdapter(reply: RelationshipResolver.Replies.OutboxParam)) =>
      if (st.senderVerKey != reply.senderVerKey || st.comMethods != reply.comMethods) {
        Effect
          .persist(OutboxParamUpdated(reply.walletId, reply.senderVerKey, reply.comMethods))
          .thenNoReply()
      } else {
        Effect
          .noReply
      }

    case (st: States.Initialized, Commands.UpdateOutboxParam(walletId, senderVerKey, comMethods)) =>
      if (st.senderVerKey != senderVerKey || st.comMethods != comMethods) {
        Effect
          .persist(OutboxParamUpdated(walletId, senderVerKey, comMethods))
          .thenNoReply()
      } else {
        Effect
          .noReply
      }
  }

  private def readCommandHandler(implicit setup: SetupOutbox): PartialFunction[(States.Initialized, Cmd), ReplyEffect[Event, State]] = {
    case (st: States.Initialized, GetOutboxParam(replyTo)) =>
      Effect
        .reply(replyTo)(RelationshipResolver.Replies.OutboxParam(st.walletId, st.senderVerKey, st.comMethods))

    case (st: States.Initialized, Commands.GetDeliveryStatus(replyTo)) =>
      Effect
        .reply(replyTo)(Replies.DeliveryStatus(st.messages))
  }

  private def eventHandler(dispatcher: Dispatcher, timer: TimerScheduler[Cmd]): (State, Event) => State = {
    case (States.Uninitialized(cfg), Events.MetadataStored(relId, recipId, destId)) =>
      val metadata = Metadata(relId, recipId, destId)
      States.MetadataReceived(Some(metadata), cfg)

    case (States.MetadataReceived(Some(metadata), cfg), OutboxParamUpdated(walletId, senderVerKey, comMethods)) =>
      dispatcher.updateDispatcher(walletId, senderVerKey, comMethods, cfg.oauthReceiveTimeoutMs)
      States.Initialized(walletId, senderVerKey, comMethods, metadata = Some(metadata), config = cfg)

    case (States.Initialized(_, _, _, msgs, Some(metadata), cfg), OutboxParamUpdated(walletId, senderVerKey, comMethods)) =>
      dispatcher.updateDispatcher(walletId, senderVerKey, comMethods, cfg.oauthReceiveTimeoutMs)
      States.Initialized(walletId, senderVerKey, comMethods, msgs, metadata = Some(metadata), cfg)

    case (st: States.Initialized, ma: Events.MsgAdded) =>
      val msg = Message(
        ma.creationTimeInMillis,
        ma.expiresAfterMillis,
        Status.MSG_DELIVERY_STATUS_PENDING.statusCode,
        Map.empty)
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

    case (st: States.Initialized, Events.ConfigUpdated(cfg)) =>
      updateTimeouts(cfg, dispatcher.outboxActorContext, timer)
      st.copy(config = cfg)

    case (st: States.Uninitialized, Events.ConfigUpdated(cfg)) =>
      updateTimeouts(cfg, dispatcher.outboxActorContext, timer)
      st.copy(config = cfg)

    case (st: States.MetadataReceived, Events.ConfigUpdated(cfg)) =>
      updateTimeouts(cfg, dispatcher.outboxActorContext, timer)
      st.copy(config = cfg)
  }

  private def signalHandler(implicit setup: SetupOutbox, config: OutboxConfig): PartialFunction[(State, Signal), Unit] = {
    case (st: State, RecoveryCompleted) =>
      st match {
        case States.Initialized(_,_,_,_,Some(metadata), _) =>
          fetchOutboxParam(metadata)
        case _ =>
      }
      if (st.config != config) {
        setup.actorContext.self ! Commands.UpdateConfig(config)
        updateTimeouts(config, setup.actorContext, setup.timer)
      } else {
        updateTimeouts(st.config, setup.actorContext, setup.timer)
      }
      setup.metricsWriter.gaugeUpdate(AS_OUTBOX_MSG_DELIVERY_PENDING_COUNT, getPendingMsgs(st).size)
      updateDispatcher(setup.dispatcher, st)

    case (_: States.Initialized, sc: SnapshotCompleted) =>
      logger.debug(s"[${setup.entityContext.entityId}] snapshot completed: " + sc)
    case (_, sf: SnapshotFailed) =>
      logger.error(s"[${setup.entityContext.entityId}] snapshot failed with error: " + sf.failure.getMessage)
    case (_, dsf: DeleteSnapshotsFailed) =>
      logger.error(s"[${setup.entityContext.entityId}] delete snapshot failed with error: " + dsf.failure.getMessage)

    case (_, dc: DeleteEventsCompleted) =>
      logger.debug(s"[${setup.entityContext.entityId}] delete events completed: " + dc)
    case (_, df: DeleteEventsFailed) =>
      logger.info(s"[${setup.entityContext.entityId}] delete events failed with error: " + df.failure.getMessage)
  }

  private def updateDispatcher(dispatcher: Dispatcher, state: State): Unit = {
    state match {
      case i: States.Initialized => dispatcher.updateDispatcher(i.walletId, i.senderVerKey, i.comMethods, i.config.oauthReceiveTimeoutMs)
      case _ => //nothing to do
    }
  }

  //after a successful attempt, decide if the message delivery can be considered as successful
  private def isMsgDelivered(comMethodId: String,
                             isItANotification: Boolean,
                             st: States.Initialized): Boolean = {
    //TODO: check/confirm this logic

    if (isItANotification) {
      false  //successful notification doesn't mean message is delivered
    } else {
      st.comMethods.get(comMethodId) match {
        case None =>
          // NOTE: it may happen that com methods got updated (while delivery was in progress) in such a way
          // that there is no com method with 'comMethodId' hence below logic handles it instead of failing
          // assumption is that this must be either "webhook" or "websocket"
          true
        case Some(cm) =>
          //for "webhook" or "websocket" com method type,
          // the successful message sending can be considered as delivered
          cm.typ == COM_METHOD_TYPE_HTTP_ENDPOINT
      }
    }
  }

  //after a failed attempt, decide if the message delivery can be considered as failed (permanently)
  private def isMsgDeliveryFailed(comMethodId: String,
                                  isItANotification: Boolean,
                                  isAnyRetryAttemptLeft: Boolean,
                                  st: States.Initialized): Boolean = {
    //TODO: check/confirm this logic
    if (isItANotification) {
      false  //failed notification doesn't mean message delivery is failed
    } else {
      st.comMethods.get(comMethodId) match {
        case None =>
          // NOTE: it may happen that com methods got updated (while delivery was in progress) in such a way
          // that there is no com method with 'comMethodId' hence below logic handles it instead of failing
          // assumption is that this must be either webhook or websocket
          false
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
        val comMethodDetail = i.comMethods.get(comMethodId).map(cm => s"$comMethodId [${cm.value}]").getOrElse("n/a")
        val activityDetail =
          s"comMethod: $comMethodDetail, " +
            s"successCount: ${deliveryAttempt.successCount}, " +
            s"failedCount: ${deliveryAttempt.failedCount}" +
            statusDetail
              .map(sd => s", statusDetail => code: ${sd.statusCode}, msg: ${sd.statusMsg}")
              .getOrElse("")
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

  private def processDelivery(st: States.Initialized)(implicit setup: SetupOutbox): Unit = {
    //process pending deliveries
    processPendingDeliveries(st)

    //remove processed messages (either delivered or permanently failed or expired)
    removeProcessedMsgs(st)
  }

  private def updateTimeouts(config: OutboxConfig, actorContext: ActorContext[Cmd], timer: TimerScheduler[Cmd]): Unit = {
    timer.cancel("process-delivery")
    timer.startTimerWithFixedDelay("process-delivery", ProcessDelivery,
      FiniteDuration.apply(config.scheduledJobIntervalMs,MILLISECONDS))
  }

  private def removeProcessedMsgs(st: State)(implicit setup: SetupOutbox): Unit = {
    //TODO: any batching here?
    st match {
      case i: States.Initialized =>
        i.messages
          .filter { case (_, msg) =>
            val expiryTimeInMillis = msg.creationTimeInMillis + msg.expiresAfterMillis
            val isExpired = expiryTimeInMillis < TimeZoneUtil.getMillisForCurrentUTCZonedDateTime
            isExpired || processedMsgStatusCodes.contains(msg.deliveryStatus)
          }.foreach { case (msgId, msg) =>
            val expiryDetail = if (!processedMsgStatusCodes.contains(msg.deliveryStatus)) s" (expired: true)" else ""
            val entityRef = ClusterSharding(setup.actorContext.system).entityRefFor(MessageMeta.TypeKey, msgId)
            entityRef ! MessageMeta.Commands.ProcessedForOutbox(
              setup.entityContext.entityId,
              msg.deliveryStatus,
              Option(MsgActivity(s"processed for outbox$expiryDetail: " + setup.entityContext.entityId)),
              setup.messageMetaReplyAdapter
            )
          }
      case _ => //nothing to do
    }
  }

  private def processPendingDeliveries(st: State)(implicit setup: SetupOutbox): Unit = {
    val pendingMsgs = getPendingMsgs(st)
    if (pendingMsgs.isEmpty) {
      setup.itemManagerEntityHelper.deregister()
    } else {
      st match {
        case i: States.Initialized =>
          pendingMsgs
            .toSeq
            .sortBy(_._2.creationTimeInMillis) //TODO: any issue with sorting here?
            .take(i.config.batchSize)
            .foreach { case (msgId, _) => sendToDispatcher(msgId, i) }
        case _ => //nothing to do
      }
    }
  }

  private def getPendingMsgs(st: State)(implicit setup: SetupOutbox): Map[MsgId, Message] = {
    st match {
      case i: States.Initialized =>
        i
          .messages
          .filter { case (msgId, msg) =>
            val expiryTime = msg.creationTimeInMillis + msg.expiresAfterMillis
            val isExpired = expiryTime < TimeZoneUtil.getMillisForCurrentUTCZonedDateTime
            ! isExpired &&
              msg.deliveryStatus == Status.MSG_DELIVERY_STATUS_PENDING.statusCode &&
                msg.deliveryAttempts.forall { case (comMethodId, msgDeliveryAttempt) =>
                  i.comMethods.get(comMethodId).exists { cm =>
                    val rp = prepareRetryParam(cm.typ, msgDeliveryAttempt.failedCount, i.config)
                    msgDeliveryAttempt.failedCount < rp.maxRetries
                  }
                }
          }
      case _ => Map.empty
    }
  }

  private def fetchOutboxParam(metadata: Metadata)
                              (implicit setup: SetupOutbox): Unit =  {
    val relResolverRef = setup.actorContext.spawnAnonymous(setup.relResolver)
    relResolverRef ! RelationshipResolver.Commands.SendOutboxParam(
      metadata.relId, metadata.destId, setup.relResolverReplyAdapter)
  }

  private val logger: Logger = getLoggerByClass(getClass)

  private def comMethodTypeTags(comMethod: Option[ComMethod]): Map[String, String] = {
    val comMethodTypeStr =
      comMethod.map(_.typ) match {
        case Some(COM_METHOD_TYPE_HTTP_ENDPOINT) =>
          "webhook-" + comMethod.flatMap(_.authentication).map(_.`type`).getOrElse("plain")
        case _ =>
          "unknown"
      }
    Map("com-method-type" -> comMethodTypeStr)
  }

  def prepareRetryParam(comMethodType: Int,
                        failedAttemptCount: Int,
                        config: OutboxConfig): RetryParam = {
    val comMethodTypeStr = comMethodType match {
      case COM_METHOD_TYPE_HTTP_ENDPOINT  => "webhook"
      case _                              => "default"
    }
    val retryPolicy = config.retryPolicy(comMethodTypeStr)
    RetryParam(
      failedAttemptCount,
      retryPolicy.maxRetries,
      FiniteDuration(retryPolicy.initialIntervalMs, MILLISECONDS)
    )
  }

  //TODO: finalize this (idea is to have one dispatcher based on com method as part of state)
  private def sendToDispatcher(msgId: MsgId,
                               state: States.Initialized)
                              (implicit setup: SetupOutbox): Unit = {
    val msg = state.messages(msgId)
    setup.dispatcher.dispatch(msgId, msg.deliveryAttempts, state.config)
  }

  private val processedMsgStatusCodes: Seq[String] = List(
    Status.MSG_DELIVERY_STATUS_SENT,
    Status.MSG_DELIVERY_STATUS_FAILED
  ).map(_.statusCode)

  def prepareEventEncryptionSalt(config: Config): String = config.getString(SALT_EVENT_ENCRYPTION)

  def prepareRetentionCriteria(config: Config): RetentionCriteria = {
    val ch = ConfigReadHelper(config)

    val rcsAfterEveryEvents: Int = ch.getIntOption(OUTBOX_RETENTION_SNAPSHOT_AFTER_EVERY_EVENTS)
      .getOrElse(DEFAULT_RETENTION_SNAPSHOT_AFTER_EVERY_EVENTS)

    val rcsKeepSnapshots: Int = ch.getIntOption(OUTBOX_RETENTION_SNAPSHOT_KEEP_SNAPSHOTS)
      .getOrElse(DEFAULT_RETENTION_SNAPSHOT_KEEP_SNAPSHOTS)

    val rcsDeleteEventsSnapshot: Boolean = ch.getBooleanOption(OUTBOX_RETENTION_SNAPSHOT_DELETE_EVENTS_ON_SNAPSHOTS)
      .getOrElse(DEFAULT_RETENTION_SNAPSHOT_DELETE_EVENTS_ON_SNAPSHOTS)

    val retentionCriteria = RetentionCriteria.snapshotEvery(numberOfEvents = rcsAfterEveryEvents,
      keepNSnapshots = rcsKeepSnapshots)

    if (rcsDeleteEventsSnapshot)
      retentionCriteria.withDeleteEventsOnSnapshot
    else
      retentionCriteria
  }

  def prepareOutboxConfig(config: Config): OutboxConfig = {
    val ch = ConfigReadHelper(config)

    val batchSize: Int = ch.getIntOption(OUTBOX_BATCH_SIZE)
      .getOrElse(DEFAULT_BATCH_SIZE)

    val receiveTimeout: Long = ch.getDurationOption(OUTBOX_RECEIVE_TIMEOUT).map(_.toMillis)
      .getOrElse(DEFAULT_RECEIVE_TIMEOUT)

    val scheduledJobInterval: Long = ch.getDurationOption(OUTBOX_SCHEDULED_JOB_INTERVAL).map(_.toMillis)
      .getOrElse(DEFAULT_SCHEDULED_JOB_INTERVAL)

    val oauthReceiveTimeout: Long = ch.getDurationOption(OUTBOX_OAUTH_RECEIVE_TIMEOUT).map(_.toMillis)
      .getOrElse(DEFAULT_OAUTH_RECEIVE_TIMEOUT)

    val retryPolicy: Map[String, RetryPolicy] = List("webhook", "default").map { name =>
      val maxRetries = ch.getIntOption(s"$OUTBOX.$name.retry-policy.max-retries")
        .getOrElse(DEFAULT_MAX_RETRIES)
      val initialInterval = ch.getDurationOption(s"$OUTBOX.$name.retry-policy.initial-interval").map(_.toMillis)
        .getOrElse(DEFAULT_INITIAL_INTERVAL)
      name -> RetryPolicy(maxRetries, initialInterval)
    }.toMap

    OutboxConfig(
      batchSize,
      receiveTimeout,
      scheduledJobInterval,
      retryPolicy,
      oauthReceiveTimeout
    )
  }

}

/**
 *
 * @param relId used to query delivery mechanism information
 * @param recipId used to limit/filter delivery mechanism information for this recipId
 * @param destId to be used to limit/filter delivery mechanism information for this destination id
 */
case class OutboxIdParam(relId: RelId, recipId: RecipId, destId: DestId) {
  def entityId: UUID = UUID.nameUUIDFromBytes((relId+recipId+destId).getBytes())
}

case class SetupOutbox(actorContext: ActorContext[Cmd],
                       entityContext: EntityContext[Cmd],
                       metricsWriter: MetricsWriter,
                       buffer: StashBuffer[Cmd],
                       dispatcher: Dispatcher,
                       relResolver: Behavior[RelationshipResolver.Cmd],
                       relResolverReplyAdapter: ActorRef[RelationshipResolver.Reply],
                       messageMetaReplyAdapter: ActorRef[MessageMeta.Reply],
                       itemManagerEntityHelper: ItemManagerEntityHelper,
                       timer: TimerScheduler[Cmd])
