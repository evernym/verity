package com.evernym.verity.msgoutbox.outbox

import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityContext, EntityTypeKey}
import akka.persistence.typed.{DeleteEventsCompleted, DeleteEventsFailed, DeleteSnapshotsFailed, PersistenceId, RecoveryCompleted, SnapshotCompleted, SnapshotFailed}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.actor.{ActorMessage, RetentionCriteriaBuilder}
import com.evernym.verity.msgoutbox._
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.MsgActivity
import com.evernym.verity.msgoutbox.outbox.Events.{MetadataStored, MsgSendingFailed, MsgSentSuccessfully, OutboxParamUpdated}
import com.evernym.verity.msgoutbox.outbox.Outbox.Cmd
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.{GetOutboxParam, MessageMetaReplyAdapter, ProcessDelivery}
import com.evernym.verity.msgoutbox.outbox.States.{Message, Metadata, MsgDeliveryAttempt}
import com.evernym.verity.msgoutbox.outbox.msg_packager.MsgPackagers
import com.evernym.verity.msgoutbox.outbox.msg_transporter.MsgTransports
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.actor.typed.base.{PersistentEventAdapter, PersistentStateAdapter}
import com.evernym.verity.config.ConfigConstants.{OUTBOX, OUTBOX_BATCH_SIZE, OUTBOX_OAUTH_RECEIVE_TIMEOUT, OUTBOX_RECEIVE_TIMEOUT, OUTBOX_RETENTION_SNAPSHOT, OUTBOX_SCHEDULED_JOB_INTERVAL, SALT_EVENT_ENCRYPTION}
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
import akka.pattern.StatusReply
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.item_store.ItemStoreEntityHelper

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

//persistent entity, holds undelivered messages
// orchestrates the delivery flow (which com method to use, retry policy etc)
// cleaning up of delivered messages
object Outbox {

  //commands
  //this trait is not sealed purposefully. It is way too hard to track all errors for scalac and it without any useful description
  //if the match is not exhaustive.
  trait Cmd extends ActorMessage
  sealed trait UninitializedCmd extends Cmd
  sealed trait MetadataReceivedCmd extends Cmd
  sealed trait InitializedCmd extends Cmd
  sealed trait GenericCmd extends Cmd
  sealed trait MessageResendCmd extends InitializedCmd
  sealed trait MessageHandlingCmd extends InitializedCmd
  sealed trait UpdateOutboxCmd extends InitializedCmd
  sealed trait TimeoutCmd extends InitializedCmd
  sealed trait ReadCmd extends InitializedCmd
  object Commands {
    case class GetOutboxParam(replyTo: ActorRef[RelationshipResolver.Replies.OutboxParam]) extends ReadCmd with UninitializedCmd with MetadataReceivedCmd
    case class GetDeliveryStatus(msgIds: List[MsgId], statuses: List[String], excludePayload: Boolean, replyTo: ActorRef[StatusReply[Replies.DeliveryStatus]]) extends ReadCmd with UninitializedCmd with MetadataReceivedCmd
    case class DeliveryStatusCollected(result: StatusReply[Replies.DeliveryStatus], replyTo: ActorRef[StatusReply[Replies.DeliveryStatus]]) extends ReadCmd

    case class RecordSuccessfulAttempt(msgId: MsgId,
                                       comMethodId: String,
                                       sendAck: Boolean,
                                       isItANotification: Boolean) extends MessageResendCmd with UninitializedCmd with MetadataReceivedCmd
    case class RecordFailedAttempt(msgId: MsgId,
                                   comMethodId: String,
                                   sendAck: Boolean,
                                   isItANotification: Boolean,
                                   isAnyRetryAttemptsLeft: Boolean,
                                   statusDetail: StatusDetail) extends MessageResendCmd with UninitializedCmd with MetadataReceivedCmd

    case class UpdateOutboxParam(result: StatusReply[WalletUpdateParam]) extends UpdateOutboxCmd with UninitializedCmd with MetadataReceivedCmd

    case class AddMsg(msgId: MsgId, expiryDuration: FiniteDuration, replyTo: ActorRef[Replies.MsgAddedReply]) extends MessageHandlingCmd with UninitializedCmd with MetadataReceivedCmd
    case class MessageMetaReplyAdapter(reply: MessageMeta.ProcessedForOutboxReply) extends MessageHandlingCmd with MetadataReceivedCmd
    case class RemoveMsg(msgId: MsgId) extends MessageHandlingCmd with UninitializedCmd with MetadataReceivedCmd

    //sent by scheduled job
    case object ProcessDelivery extends TimeoutCmd with UninitializedCmd with MetadataReceivedCmd

    case class Init(relId: RelId, recipId: RecipId, destId: DestId, replyTo: ActorRef[Replies.Initialized]) extends UninitializedCmd

    case class UpdateConfig(config: OutboxConfig) extends GenericCmd
  }

  trait Reply extends ActorMessage
  sealed trait GetDeliveryStatusReply extends Reply
  object Replies {
    trait MsgAddedReply extends Reply
    case object MsgAlreadyAdded extends MsgAddedReply
    case object MsgAdded extends MsgAddedReply
    case class NotInitialized(entityId: String) extends MsgAddedReply
    // we should get rid of the entityId there as soon as we deprecate the OutboxRouter
    case class Initialized(entityId: String) extends Reply
    case class DeliveryStatus(messages: List[MsgDetail]) extends GetDeliveryStatusReply
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
            relResolver: RelResolver,
            msgPackagers: MsgPackagers,
            msgTransports: MsgTransports,
            executionContext: ExecutionContext,
            msgRepository: MessageRepository): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val config = prepareOutboxConfig(configuration)
      val eventEncryptionSalt = prepareEventEncryptionSalt(configuration)
      val retentionCriteria = prepareRetentionCriteria(configuration)
      Behaviors.withTimers { timer =>
        Behaviors.withStash(100) { buffer =>                     //TODO: finalize this
          val messageMetaReplyAdapter = actorContext.messageAdapter(reply => MessageMetaReplyAdapter(reply))
          val dispatcher = new Dispatcher(
            actorContext,
            accessTokenRefreshers,
            eventEncryptionSalt,
            msgRepository,
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
            messageMetaReplyAdapter,
            new ItemStoreEntityHelper(entityContext.entityId, TypeKey.name, actorContext.system),
            timer,
            msgRepository
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

  private def commandHandler(setup: SetupOutbox): (State, Cmd) => ReplyEffect[Event, State] = {
    case (_: States.Uninitialized, cmd: UninitializedCmd) => uninitializedStateHandler (setup)(cmd)
    case (_: States.MetadataReceived, cmd: MetadataReceivedCmd) =>  metadataReceivedStateHandler (setup)(cmd)
    case (st: States.Initialized, cmd: InitializedCmd) => initializedStateHandler (st)(setup)(cmd)
    case (_, cmd: GenericCmd) => genericStateHandler()(cmd)
    //this case just prevents exception from being thrown. this match is not covered by exhaustive matching check!
    case (st, cmd) =>
      logger.error(s"Unexpected message received. state: ${st}, cmd: ${cmd}")
      Effect.noReply
  }

  private def genericStateHandler(): GenericCmd => ReplyEffect[Event, State] = {
    case Commands.UpdateConfig(cfg) =>
      Effect
        .persist(Events.ConfigUpdated(cfg))
        .thenNoReply()
  }

  private def uninitializedStateHandler(implicit setup: SetupOutbox): UninitializedCmd => ReplyEffect[Event, State] = {
    case cmd @ Commands.AddMsg(_, _, replyTo) =>
//      setup.buffer.stash(cmd)
      Effect
        .reply(replyTo)(Replies.NotInitialized(setup.entityContext.entityId))

    case Commands.Init(relId, recipId, destId, replyTo) =>
      Effect
        .persist(MetadataStored(relId = relId, recipId = recipId, destId = destId))
        .thenRun((_: State) => fetchOutboxParam(Metadata(relId, recipId, destId)))
        .thenReply(replyTo)(_ => Replies.Initialized(setup.entityContext.entityId))

    case cmd =>
      setup.buffer.stash(cmd)
      Effect
        .noReply
  }

  private def metadataReceivedStateHandler(setup: SetupOutbox): MetadataReceivedCmd => ReplyEffect[Event, State] = {
    case Commands.UpdateOutboxParam(StatusReply.Success(WalletUpdateParam(walletId, senderVerKey, comMethods))) =>
      Effect
        .persist(OutboxParamUpdated(walletId, senderVerKey, comMethods))
        .thenRun((_: State) => setup.buffer.unstashAll(Behaviors.same))
        .thenNoReply()

    case Commands.UpdateOutboxParam(StatusReply.Error(e)) =>
      logger.warn(s"Exception occurred ${e}")
      Effect.noReply

    case cmd =>
      setup.buffer.stash(cmd)
      Effect
        .noReply
  }

  private def initializedStateHandler(st: States.Initialized)(implicit setup: SetupOutbox): InitializedCmd => ReplyEffect[Event, State] = {
    case cmd: MessageResendCmd => messageResendCommandHandler(st)(setup)(cmd)
    case cmd: MessageHandlingCmd => messageHandlingCommandHandler(st)(setup)(cmd)
    case cmd: TimeoutCmd => timeoutCommandHandler(st)(setup)(cmd)
    case cmd: UpdateOutboxCmd => updateConfigCommandHandler(st)(setup)(cmd)
    case cmd: ReadCmd => readCommandHandler(st)(setup)(cmd)
  }

  private def messageResendCommandHandler(st: States.Initialized)(implicit setup: SetupOutbox): MessageResendCmd => ReplyEffect[Event, State] = {
    case rsa: Commands.RecordSuccessfulAttempt =>
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

    case rfa: Commands.RecordFailedAttempt =>
      val isDeliveryFailed = isMsgDeliveryFailed(rfa.comMethodId,
        rfa.isItANotification, rfa.isAnyRetryAttemptsLeft, st)
      Effect
        .persist(MsgSendingFailed(rfa.msgId, rfa.comMethodId, isDeliveryFailed))
        .thenRun((_: State) => setup.itemStoreEntityHelper.register())
        .thenRun((_: State) => if (rfa.sendAck) setup.dispatcher.ack(rfa.msgId))
        .thenRun((_: State) => if (isDeliveryFailed && ! rfa.isItANotification) {
          setup.metricsWriter.gaugeIncrement(AS_OUTBOX_MSG_DELIVERY_FAILED_COUNT, tags = comMethodTypeTags(st.comMethods.get(rfa.comMethodId)))
          setup.metricsWriter.gaugeDecrement(AS_OUTBOX_MSG_DELIVERY_PENDING_COUNT)
        })
        .thenRun((st: State) => sendMsgActivityToMessageMeta(st, rfa.msgId, rfa.comMethodId, Option(rfa.statusDetail)))
        .thenNoReply()
  }

  private def messageHandlingCommandHandler(st: States.Initialized)(implicit setup: SetupOutbox): MessageHandlingCmd => ReplyEffect[Event, State] = {
    case Commands.AddMsg(msgId, expiryDuration, replyTo) =>
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

    case MessageMetaReplyAdapter(reply: MessageMeta.Replies.RemoveMsg) =>
      if (st.messages.contains(reply.msgId)) {
        Effect
          .persist(Events.MsgRemoved(reply.msgId))
          .thenNoReply()
      } else Effect.noReply

    case Commands.RemoveMsg(msgId) =>
      if (st.messages.contains(msgId)) {
        Effect
          .persist(Events.MsgRemoved(msgId))
          .thenNoReply()
      } else Effect.noReply
  }

  private def timeoutCommandHandler(st: States.Initialized)(implicit setup: SetupOutbox): TimeoutCmd => ReplyEffect[Event, State] = {
    case Commands.ProcessDelivery =>
      processDelivery(st)
      Effect
        .noReply
  }

  @annotation.nowarn
  private def updateConfigCommandHandler(st: States.Initialized)(implicit setup: SetupOutbox): UpdateOutboxCmd => ReplyEffect[Event, State] = {
    case Commands.UpdateOutboxParam(StatusReply.Success(WalletUpdateParam(walletId, senderVerKey, comMethods))) =>
      if (st.senderVerKey != senderVerKey || st.comMethods != comMethods) {
        Effect
          .persist(OutboxParamUpdated(walletId, senderVerKey, comMethods))
          .thenNoReply()
      } else {
        Effect
          .noReply
      }

    case Commands.UpdateOutboxParam(StatusReply.Error(e)) =>
      logger.warn(s"Exception occurred ${e}")
      Effect.noReply
  }

  private def readCommandHandler(st: States.Initialized)(implicit setup: SetupOutbox): ReadCmd => ReplyEffect[Event, State] = {
    case GetOutboxParam(replyTo) =>
      //TODO: do not use other actor's commands as reply message
      Effect
        .reply(replyTo)(RelationshipResolver.Replies.OutboxParam(st.walletId, st.senderVerKey, st.comMethods))

    case Commands.GetDeliveryStatus(filterMsgIds, statuses, excludePayload, replyTo) =>
      val requestIds = st.messages.filter{
        p => (filterMsgIds.isEmpty || filterMsgIds.contains(p._1)) && (statuses.isEmpty || statuses.contains(p._2.deliveryStatus))
      }.keys.toList
      val statusesByIds = st.messages.map{p => p._1 -> p._2.deliveryStatus}
      val readFuture = setup.msgRepository.read(requestIds, excludePayload)

      setup.actorContext.pipeToSelf(readFuture){
        case Success(value) => {
          val result = value.map(msg => MsgDetail(
            msg.id,
            msg.`type`,
            msg.legacyPayload.map(_.senderDID).getOrElse(""), //TODO fix this case of DID that has no values
            statusesByIds(msg.id),
            msg.legacyPayload.flatMap(_.refMsgId),
            None, // it is None for now, Rajesh will clarify the need for the thread
            msg.payload,
            Set()
          ))
          Commands.DeliveryStatusCollected(StatusReply.Success(Replies.DeliveryStatus(result)), replyTo)
        }
        case Failure(ex)    => Commands.DeliveryStatusCollected(StatusReply.Error(ex), replyTo)
      }

      Effect
        .noReply

    case Commands.DeliveryStatusCollected(resp, replyTo) =>
      Effect.reply(replyTo)(resp)
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
      setup.itemStoreEntityHelper.deregister()
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
    setup.actorContext.pipeToSelf(
      setup.relResolver.getWalletParam(metadata.relId, metadata.destId)
    ) {
      case Success(value) => Commands.UpdateOutboxParam(StatusReply.Success(WalletUpdateParam(value._1, value._2, value._3)))
      case Failure(e) => Commands.UpdateOutboxParam(StatusReply.Error(e))
    }
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
    RetentionCriteriaBuilder.build(
      config,
      OUTBOX_RETENTION_SNAPSHOT,
      DEFAULT_RETENTION_SNAPSHOT_AFTER_EVERY_EVENTS,
      DEFAULT_RETENTION_SNAPSHOT_KEEP_SNAPSHOTS,
      DEFAULT_RETENTION_SNAPSHOT_DELETE_EVENTS_ON_SNAPSHOTS
    )
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

  final val DESTINATION_ID_DEFAULT = "default"
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

case class WalletUpdateParam(walletId: String, senderVerKey: VerKeyStr, comMethods: Map[ComMethodId, ComMethod])

case class SetupOutbox(actorContext: ActorContext[Cmd],
                       entityContext: EntityContext[Cmd],
                       metricsWriter: MetricsWriter,
                       buffer: StashBuffer[Cmd],
                       dispatcher: Dispatcher,
                       relResolver: RelResolver,
                       messageMetaReplyAdapter: ActorRef[MessageMeta.ProcessedForOutboxReply],
                       itemStoreEntityHelper: ItemStoreEntityHelper,
                       timer: TimerScheduler[Cmd],
                       msgRepository: MessageRepository)
