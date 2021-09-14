package com.evernym.verity.msgoutbox

import java.util.concurrent.TimeUnit

import akka.actor.ActorContext
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.pattern.StatusReply
import akka.util.Timeout
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.util2.RetentionPolicy

import scala.concurrent.{ExecutionContext, Future}

class OutboxService(val msgStore: ActorRef[MsgStore.Cmd], relResolver: RelResolver, msgRepository: MessageRepository, timeout: Option[Timeout] = None)
                   (implicit ec: ExecutionContext, actorContext: ActorContext, agentMsgRouter: AgentMsgRouter) {
  val system: ActorSystem[_] = actorContext.system.toTyped
  val clusterSharding: ClusterSharding = ClusterSharding(system)
  implicit val tmt: Timeout = timeout.getOrElse(Timeout(5, TimeUnit.SECONDS))
  implicit val scheduler: Scheduler = system.scheduler

  def sendMessage(
                   relRecipId: Map[RelId, RecipId],
                   msg: String,
                   msgType: String,
                   retentionPolicy: RetentionPolicy
                 ): Future[MsgId] = {
    for {
      outboxIdParams <- Future.sequence(relRecipId.map(rr => relResolver.resolveOutboxParam(rr._1, rr._2)).toSet)
      msgId <- msgRepository.insert(msgType, msg, retentionPolicy, outboxIdParams)
      _ <- outboxAddMsgs(outboxIdParams, msgId, retentionPolicy)
    } yield msgId
  }

  //FIRST IMPLEMENTATION OF GET_MSGS
  def getMessages(
                   relId: RelId,
                   recipId: RecipId,
                   msgIds: List[MsgId],
                   statuses: List[String],
                   excludePayload: Boolean
                 ): Future[Seq[MsgDetail]] = {
    for {
      outboxIdParam <- relResolver.resolveOutboxParam(relId, recipId)
      Outbox.Replies.DeliveryStatus(messages) <- clusterSharding.entityRefFor(Outbox.TypeKey, outboxIdParam.entityId.toString)
            .askWithStatus(ref => Outbox.Commands.GetDeliveryStatus(msgIds, statuses, excludePayload, ref))
    } yield messages
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
}
