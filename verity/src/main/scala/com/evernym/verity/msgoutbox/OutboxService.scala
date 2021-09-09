package com.evernym.verity.msgoutbox

import akka.actor.ActorContext
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.util.Timeout
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.util2.RetentionPolicy

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class OutboxService(val msgStore: ActorRef[MsgStore.Cmd], relResolver: IRelResolver, msgRepository: IMessageRepository)
                   (implicit ec: ExecutionContext, actorContext: ActorContext, agentMsgRouter: AgentMsgRouter) {
  val system: ActorSystem[_] = actorContext.system.toTyped
  val clusterSharding: ClusterSharding = ClusterSharding(system)
  implicit val timeout: Timeout = ???
  implicit val scheduler: Scheduler = ???

  def sendMessage(
                   relRecipId: Map[RelId, RecipId],
                   msg: String,
                   msgType: String,
                   retentionPolicy: RetentionPolicy
                 ): Future[MsgId] = {
    for {
      outboxIdParams <- Future.sequence(relRecipId.map(rr => relResolver.resolveOutboxParam(rr._1, rr._2)).toSet)
      msgId <- msgRepository.createMessage(msgType, msg, retentionPolicy, outboxIdParams)
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
                 ): Future[Seq[Try[MsgDetail]]] = {
    for {
      outboxIdParam <- relResolver.resolveOutboxParam(relId, recipId)
      Outbox.Replies.DeliveryStatus(messages)
        <- clusterSharding.entityRefFor(Outbox.TypeKey, outboxIdParam.entityId.toString).ask(ref => Outbox.Commands.GetDeliveryStatus(ref))
      //TODO: do we need partial results?
      result <- Future.sequence(messages
        .filter{
          p => (msgIds.isEmpty || msgIds.contains(p._1)) && (statuses.isEmpty || statuses.contains(p._2.deliveryStatus))
        }
        .map {
          case (id, msgDetail) => msgRepository.getMessage(id, msgDetail.deliveryStatus, excludePayload)
        }.toSeq)
    } yield result
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
