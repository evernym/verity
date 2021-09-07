package com.evernym.verity.msgoutbox.api.future

import java.util.UUID

import akka.actor.ActorContext
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.{DestId, MsgId, RecipId, RelId}
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.GetDeliveryStatus
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.msgoutbox.router.OutboxRouter.DESTINATION_ID_DEFAULT
import com.evernym.verity.util2.RetentionPolicy
import com.evernym.verity.util2.Status.StatusDetail

import scala.concurrent.{ExecutionContext, Future}

class IOutbox(val msgStore: ActorRef[MsgStore.Cmd])(implicit ec: ExecutionContext, actorContext: ActorContext, agentMsgRouter: AgentMsgRouter) {
  val system: ActorSystem[_] = actorContext.system.toTyped
  val clusterSharding: ClusterSharding = ClusterSharding(system)
  implicit val timeout: Timeout = ???
  implicit val scheduler: Scheduler = ???

  def sendMessage(
                   relId: RelId,
                   recipId: RecipId,
                   msg: String,
                   msgType: String,
                   retentionPolicy: RetentionPolicy
                 ): Future[MsgId] = {
    val outboxRefFuture = resolveOutbox(relId, recipId)
    val msgId = UUID.randomUUID().toString
    val storeMsgData = storeMsg(msgId, msg, retentionPolicy)
    for {
      outboxIdParam <- outboxRefFuture
      _ <- storeMsgData
      _ <- createMessageMeta(msgId, msgType, retentionPolicy, Set(outboxIdParam))
      _ <- outboxAddMsgs(Set(outboxIdParam), msgId, retentionPolicy)
    } yield msgId
  }

  private def resolveOutbox(relId: RelId, recipId: RecipId): Future[OutboxIdParam] = {
    val relationshipResolver = RelationshipResolver(agentMsgRouter)
    val relationshipResolverRef: ActorRef[RelationshipResolver.Cmd] = actorContext.spawnAnonymous(relationshipResolver)
    for {
      RelationshipResolver.Replies.RelParam(selfRelId, relationship) <- relationshipResolverRef.ask(ref => RelationshipResolver.Commands.GetRelParam(relId, ref))
    } yield {
      val (relIdToBeUsed, recipIdToBeUsed) =
        if (relationship.exists(_.relationshipType == PAIRWISE_RELATIONSHIP) &&
          relationship.exists(_.theirDidDoc.exists(_.did == recipId))) {
          (relId, recipId)
        } else {
          (selfRelId, selfRelId)
        }
      OutboxIdParam(relIdToBeUsed, recipIdToBeUsed, DESTINATION_ID_DEFAULT)
    }
  }

  private def storeMsg(msgId: MsgId, msg: String, retentionPolicy: RetentionPolicy): Future[Unit] = {
    for {
      _ <- msgStore.ask(ref => MsgStore.Commands.StorePayload(msgId, msg.getBytes, retentionPolicy, ref))
    } yield {}
  }

  private def createMessageMeta(msgId: MsgId, msgType: String, retentionPolicy: RetentionPolicy, outboxIdParams: Set[OutboxIdParam]): Future[Unit] = {
    val messageMetaRef = clusterSharding.entityRefFor(MessageMeta.TypeKey, msgId)
    for {
      _ <- messageMetaRef.ask(ref => MessageMeta.Commands.Add(msgType, retentionPolicy.configString, outboxIdParams.map(_.entityId.toString).toSet, None, None, ref))
    } yield {}
  }

  private def outboxAddMsgs(outboxIdParams: Set[OutboxIdParam], msgId: MsgId, retentionPolicy: RetentionPolicy): Future[Unit] = {
    //for now we are not collecting AddMsg
    //we need to refactor interaction on Init message and collect replies from it as well (and make it askable)
    for {
      outboxResponses <- Future.sequence(outboxIdParams.map{
        param => clusterSharding.entityRefFor(Outbox.TypeKey, param.entityId.toString)
          .ask(ref => Outbox.Commands.AddMsg(msgId, retentionPolicy.elements.expiryDuration, ref))
      }.toSeq)
    } yield outboxResponses.map {
      case Outbox.Replies.NotInitialized(entityId) => {
        val outboxRef: EntityRef[Outbox.Cmd] = clusterSharding.entityRefFor(Outbox.TypeKey, entityId)
        //TODO: this looks bad. we have an OutboxIdParam on the previous step, we should be able to save it along with the future.
        outboxIdParams
          .filter(_.entityId.toString == entityId)
          .map(p => outboxRef.tell(Outbox.Commands.Init(p.relId, p.recipId, p.destId)))
      }
    }
  }


  //FIRST IMPLEMENTATION OF GET_MSGS

  def getMessages(
                   relId: RelId,
                   recipId: RecipId,
                   msgIds: List[MsgId],
                   statuses: List[StatusDetail],
                   excludePayload: Boolean
                 ): Future[Seq[MsgDetail]] = {
    for {
      outboxIdParam <- resolveOutbox(relId, recipId)
      Outbox.Replies.DeliveryStatus(messages)
        <- clusterSharding.entityRefFor(Outbox.TypeKey, outboxIdParam.entityId.toString).ask(ref => Outbox.Commands.GetDeliveryStatus(ref))
      //TODO: do we need partial results?
      result <- Future.sequence(messages
        .filter{
          p => (msgIds.isEmpty || msgIds.contains(p._1) && (statuses.isEmpty || statuses.contains(p._2.deliveryStatus)))
        }
        .map {
          case (id, msgDetail) => getMsgDetail(id, msgDetail.deliveryStatus, excludePayload)
        }.toSeq)
    } yield result
  }

  private def getMsgDetail(id: MsgId, deliveryStatus: String, excludePayload: Boolean) : Future[MsgDetail] = {
    val messageMeta = clusterSharding.entityRefFor(MessageMeta.TypeKey, id)
    val f: Future[MessageMeta.Replies.GetMsgReply] = messageMeta.ask(ref => MessageMeta.Commands.Get(ref))
    f.flatMap {
      case MessageMeta.Replies.MsgNotYetAdded =>
        Future.failed(new Exception()) // TODO: fix exception
      case MessageMeta.Replies.Msg(id, typ, legacy, payload) =>
        Future.successful(MsgDetail(
          id,
          typ,
          legacy.get.senderDID, // TODO: fix unchecked get
          deliveryStatus,
          legacy.flatMap(_.refMsgId),
          ???, // TODO: add thread
          if (excludePayload) None else payload,
          Set()
        ))
    }
  }
}
