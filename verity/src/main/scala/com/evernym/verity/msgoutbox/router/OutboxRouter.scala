package com.evernym.verity.msgoutbox.router

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.evernym.verity.util2.RetentionPolicy
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore.Commands.StorePayload
import com.evernym.verity.msgoutbox.{MsgId, OutboxId, RecipId, RelId}
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Replies.RelParam
import com.evernym.verity.msgoutbox.router.OutboxRouter.Commands.{MessageMetaReplyAdapter, MsgStoreReplyAdapter, OutboxReplyAdapter, RelResolverReplyAdapter, SendMsg}
import java.util.UUID

import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

object OutboxRouter {

  sealed trait Cmd extends ActorMessage
  object Commands {
    case object SendMsg extends Cmd
    case class RelResolverReplyAdapter(reply: RelationshipResolver.Reply) extends Cmd
    case class MsgStoreReplyAdapter(reply: MsgStore.Reply) extends Cmd
    case class MessageMetaReplyAdapter(reply: MessageMeta.Reply) extends Cmd
    case class OutboxReplyAdapter(reply: Outbox.Reply) extends Cmd
  }

  trait Reply extends ActorMessage
  object Replies {
    case class Ack(msgId: MsgId, targetOutboxIds: Seq[OutboxId]) extends Reply
  }

  private val logger: Logger = getLoggerByClass(getClass)

  def apply(relId: RelId,
            recipId: RecipId,
            msg: String,
            msgType: String,
            retentionPolicy: RetentionPolicy,
            relResolver: Behavior[RelationshipResolver.Cmd],
            msgStore: ActorRef[MsgStore.Cmd],
            replyTo:Option[ActorRef[Reply]]=None): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      Behaviors.withStash(10) { buffer =>
        val relResolverReplyAdapter = actorContext.messageAdapter(reply => RelResolverReplyAdapter(reply))
        val relResolverRef = actorContext.spawnAnonymous(relResolver)
        relResolverRef ! RelationshipResolver.Commands.GetRelParam(relId, relResolverReplyAdapter)
        initializing(relId, recipId, msg, msgType, retentionPolicy, msgStore, replyTo)(actorContext, buffer)
      }
    }
  }

  def initializing(relId: RelId,
                   recipId: RecipId,
                   msg: String,
                   msgType: String,
                   retentionPolicy: RetentionPolicy,
                   msgStore: ActorRef[MsgStore.Cmd],
                   replyTo:Option[ActorRef[Reply]])
                  (implicit actorContext: ActorContext[Cmd],
                   buffer: StashBuffer[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case RelResolverReplyAdapter(reply: RelationshipResolver.Replies.RelParam) =>
      buffer.unstashAll(storeMsgPayload(relId, recipId, reply, msg, msgType, retentionPolicy, msgStore, replyTo))
    case cmd =>
      buffer.stash(cmd)
      Behaviors.same
  }

  def storeMsgPayload(relId: RelId,
                      recipId: RecipId,
                      relParam: RelParam,
                      msg: String,
                      msgType: String,
                      retentionPolicy: RetentionPolicy,
                      msgStore: ActorRef[MsgStore.Cmd],
                      replyTo:Option[ActorRef[Reply]])
                     (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case SendMsg =>
      val msgId = UUID.randomUUID().toString
      val msgStoreReplyAdapter = actorContext.messageAdapter(reply => MsgStoreReplyAdapter(reply))
      msgStore ! StorePayload(msgId, msg.getBytes, retentionPolicy, msgStoreReplyAdapter)
      handleMsgPayloadStored(msgId, msgType, retentionPolicy, relId, recipId, relParam, replyTo)
    case cmd =>
      logger.error(s"Unexpected message received in WaitingForOutboxReply ${cmd}")
      Behaviors.same
  }

  def handleMsgPayloadStored(msgId: MsgId,
                             msgType: String,
                             retentionPolicy: RetentionPolicy,
                             relId: RelId,
                             recipId: RecipId,
                             relParam: RelParam,
                             replyTo:Option[ActorRef[Reply]])
                            (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case MsgStoreReplyAdapter(MsgStore.Replies.PayloadStored) =>
      val clusterSharding = ClusterSharding(actorContext.system)
      val outboxIdParams = Seq(
        {
          //for one destination (doesn't matter which relationship it is),
          // it has to belong to same outbox always
          // (oAuthAccessTokenHolder is kept inside an Outbox with an assumption that
          // for the same 'destination' there will be one outbox)
          val (relIdToBeUsed, recipIdToBeUsed) =
            if (relParam.relationship.exists(_.relationshipType == PAIRWISE_RELATIONSHIP) &&
              relParam.relationship.exists(_.theirDidDoc.exists(_.did == recipId))) {
              (relId, recipId)
            } else {
              (relParam.selfRelId, relParam.selfRelId)
            }

          //NOTE: the last parameter below is 'destination id',
          // for legacy use cases (where we always supported one destination) it will be always 'default',
          // but as soon as we start supporting more than one destinations, this will change accordingly.
          OutboxIdParam(relIdToBeUsed, recipIdToBeUsed, DESTINATION_ID_DEFAULT)
        }
      )

      val msgMetaReplyAdapter = actorContext.messageAdapter(reply => MessageMetaReplyAdapter(reply))

      val messageMetaEntityRef = clusterSharding.entityRefFor(MessageMeta.TypeKey, msgId)
      messageMetaEntityRef ! MessageMeta.Commands.Add(
        msgType,
        retentionPolicy.configString,
        outboxIdParams.map(_.entityId.toString).toSet,
        None,
        None,
        msgMetaReplyAdapter
      )
      handleMsgMetaStored(msgId, retentionPolicy, outboxIdParams, replyTo)
    case cmd =>
      logger.error(s"Unexpected message received in WaitingForOutboxReply ${cmd}")
      Behaviors.same
  }

  def handleMsgMetaStored(msgId: MsgId,
                          retentionPolicy: RetentionPolicy,
                          outboxIdParams: Seq[OutboxIdParam],
                          replyTo:Option[ActorRef[Reply]])
                         (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case MessageMetaReplyAdapter(MessageMeta.Replies.MsgAdded) =>
      //TODO: finalize below logic
      val clusterSharding = ClusterSharding(actorContext.system)
      val outboxReplyAdapter = actorContext.messageAdapter(reply => OutboxReplyAdapter(reply))
      outboxIdParams.foreach { outboxIdParam =>
        val outboxEntityRef = clusterSharding.entityRefFor(Outbox.TypeKey, outboxIdParam.entityId.toString)
        outboxEntityRef ! Outbox.Commands.AddMsg(msgId, retentionPolicy.elements.expiryDuration, outboxReplyAdapter)
      }
      waitingForOutboxReply(msgId, outboxIdParams, 0, replyTo)
    case cmd =>
      logger.error(s"Unexpected message received in WaitingForOutboxReply ${cmd}")
      Behaviors.same
  }

  def waitingForOutboxReply(msgId: MsgId,
                            targetOutboxIds: Seq[OutboxIdParam],
                            ackReceivedCount: Int,
                            replyTo:Option[ActorRef[Reply]])
                           (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case OutboxReplyAdapter(Outbox.Replies.MsgAdded) =>
      val totalAckReceived = ackReceivedCount + 1
      if (totalAckReceived == targetOutboxIds.size) {
        replyTo.foreach(_ ! Replies.Ack(msgId, targetOutboxIds.map(_.entityId.toString)))
        Behaviors.stopped
      } else {
        waitingForOutboxReply(msgId, targetOutboxIds, totalAckReceived, replyTo)
      }
    case OutboxReplyAdapter(Outbox.Replies.NotInitialized(entityId)) =>
      val clusterSharding = ClusterSharding(actorContext.system)
      val outboxEntityRef = clusterSharding.entityRefFor(Outbox.TypeKey, entityId)
      targetOutboxIds
        .filter(_.entityId.toString == entityId)
        .foreach(outboxParam => outboxEntityRef ! Outbox.Commands.Init(outboxParam.relId, outboxParam.recipId, outboxParam.destId))

      waitingForOutboxReply(msgId, targetOutboxIds, ackReceivedCount, replyTo)
    case cmd =>
      logger.error(s"Unexpected message received in WaitingForOutboxReply ${cmd}")
      Behaviors.same
  }

  final val DESTINATION_ID_DEFAULT = "default"
}