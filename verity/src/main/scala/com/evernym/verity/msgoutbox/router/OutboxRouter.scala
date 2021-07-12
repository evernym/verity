package com.evernym.verity.msgoutbox.router

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.pattern.StatusReply
import com.evernym.verity.util2.RetentionPolicy
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore.Commands.StorePayload
import com.evernym.verity.msgoutbox.{MsgId, ParticipantId, RecipId, RelId}
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Replies.RelParam
import com.evernym.verity.msgoutbox.router.OutboxRouter.Commands.{MessageMetaReplyAdapter, MsgStoreReplyAdapter, OutboxReplyAdapter, RelResolverReplyAdapter, SendMsg}
import com.evernym.verity.util.ParticipantUtil

import java.util.UUID

object OutboxRouter {

  trait Cmd extends ActorMessage

  object Commands {
    case object SendMsg extends Cmd
    case class RelResolverReplyAdapter(reply: RelationshipResolver.Reply) extends Cmd
    case class MsgStoreReplyAdapter(reply: MsgStore.Reply) extends Cmd
    case class MessageMetaReplyAdapter(reply: StatusReply[MessageMeta.Reply]) extends Cmd
    case class OutboxReplyAdapter(reply: StatusReply[Outbox.Reply]) extends Cmd
  }

  def apply(fromParticipantId: ParticipantId,
            toParticipantId: ParticipantId,
            msg: String,
            msgType: String,
            retentionPolicy: RetentionPolicy,
            relResolver: Behavior[RelationshipResolver.Cmd],
            msgStore: ActorRef[MsgStore.Cmd]): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      Behaviors.withStash(10) { buffer =>
        val relId = ParticipantUtil.DID(fromParticipantId)
        val recipId = ParticipantUtil.DID(toParticipantId)
        val relResolverReplyAdapter = actorContext.messageAdapter(reply => RelResolverReplyAdapter(reply))
        val relResolverRef = actorContext.spawnAnonymous(relResolver)
        relResolverRef ! RelationshipResolver.Commands.GetRelParam(relId, relResolverReplyAdapter)
        initializing(relId, recipId, msg, msgType, retentionPolicy, msgStore)(actorContext, buffer)
      }
    }
  }

  def initializing(relId: RelId,
                   recipId: RecipId,
                   msg: String,
                   msgType: String,
                   retentionPolicy: RetentionPolicy,
                   msgStore: ActorRef[MsgStore.Cmd])
                  (implicit actorContext: ActorContext[Cmd],
                   buffer: StashBuffer[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case RelResolverReplyAdapter(reply: RelationshipResolver.Replies.RelParam) =>
      buffer.unstashAll(storeMsgPayload(relId, recipId, reply, msg, msgType, retentionPolicy, msgStore))
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
                      msgStore: ActorRef[MsgStore.Cmd])
                     (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case SendMsg =>
      val msgId = UUID.randomUUID().toString
      val msgStoreReplyAdapter = actorContext.messageAdapter(reply => MsgStoreReplyAdapter(reply))
      msgStore ! StorePayload(msgId, msg.getBytes, retentionPolicy, msgStoreReplyAdapter)
      handleMsgPayloadStored(msgId, msgType, retentionPolicy, relId, recipId, relParam)
  }

  def handleMsgPayloadStored(msgId: MsgId,
                             msgType: String,
                             retentionPolicy: RetentionPolicy,
                             relId: RelId,
                             recipId: RecipId,
                             relParam: RelParam)
                            (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case MsgStoreReplyAdapter(MsgStore.Replies.PayloadStored) =>
      //TODO: finalize this
      val clusterSharding = ClusterSharding(actorContext.system)
      val outboxIdParams = Seq(
        {
          val relIdToBeUsed =
            if (relParam.relationship.exists(_.relationshipType == PAIRWISE_RELATIONSHIP) &&
              relParam.relationship.exists(_.theirDidDoc.exists(_.did == recipId))) {
              relId
            } else {
              relParam.selfRelId
            }
          OutboxIdParam(relIdToBeUsed, recipId, "default")
        }
      )

      val msgMetaReplyAdapter = actorContext.messageAdapter(reply => MessageMetaReplyAdapter(reply))

      val messageMetaEntityRef = clusterSharding.entityRefFor(MessageMeta.TypeKey, msgId)
      messageMetaEntityRef ! MessageMeta.Commands.Add(
        msgType, retentionPolicy.configString, outboxIdParams.map(_.outboxId).toSet, None, None, msgMetaReplyAdapter)
      handleMsgMetaStored(msgId, outboxIdParams)
  }

  def handleMsgMetaStored(msgId: MsgId,
                          outboxIdParams: Seq[OutboxIdParam])
                         (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case MessageMetaReplyAdapter(_: StatusReply[MessageMeta.Reply]) =>  //TODO: come back to this
      //TODO: finalize this
      val clusterSharding = ClusterSharding(actorContext.system)

      val outboxReplyAdapter = actorContext.messageAdapter(reply => OutboxReplyAdapter(reply))
      outboxIdParams.foreach { outboxIdParam =>
        val outboxEntityRef = clusterSharding.entityRefFor(Outbox.TypeKey, outboxIdParam.outboxId)
        outboxEntityRef ! Outbox.Commands.AddMsg(msgId, outboxReplyAdapter)
      }
      waitingForOutboxReply
  }

  def waitingForOutboxReply(implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case OutboxReplyAdapter(_: StatusReply[Outbox.Reply]) =>  //TODO: come back to this
      Behaviors.stopped
  }
}