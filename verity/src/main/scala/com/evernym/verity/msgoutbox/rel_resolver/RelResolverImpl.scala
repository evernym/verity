package com.evernym.verity.msgoutbox.rel_resolver

import java.util.concurrent.TimeUnit

import akka.actor.ActorContext
import akka.actor.typed.{ActorRef, Scheduler}
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.msgoutbox.router.OutboxRouter.DESTINATION_ID_DEFAULT
import com.evernym.verity.msgoutbox.{RecipId, RelId, RelResolver}

import scala.concurrent.{ExecutionContext, Future}

class RelResolverImpl(timeout: Option[Timeout] = None)(implicit val executionContext: ExecutionContext, agentMsgRouter: AgentMsgRouter, actorContext: ActorContext) extends RelResolver {
  implicit val tmt: Timeout = timeout.getOrElse(Timeout(5, TimeUnit.SECONDS))
  implicit val scheduler: Scheduler = actorContext.system.toTyped.scheduler
  override def resolveOutboxParam(relId: RelId, recipId: RecipId): Future[OutboxIdParam] = {
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
}