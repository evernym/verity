package com.evernym.verity.msgoutbox.rel_resolver

import java.util.concurrent.TimeUnit

import akka.util.Timeout
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.msgoutbox.router.OutboxRouter.DESTINATION_ID_DEFAULT
import com.evernym.verity.msgoutbox.{ComMethod, ComMethodId, DestId, RecipId, RelId, RelResolver, WalletId}

import scala.concurrent.{ExecutionContext, Future}

class RelResolverImpl(timeout: Option[Timeout] = None)
                     (implicit val executionContext: ExecutionContext, agentMsgRouter: AgentMsgRouter) extends RelResolver {
  implicit val tmt: Timeout = timeout.getOrElse(Timeout(5, TimeUnit.SECONDS))
  override def resolveOutboxParam(relId: RelId, recipId: RecipId): Future[OutboxIdParam] = {
    for {
      RelationshipResolver.Replies.RelParam(selfRelId, relationship) <- agentMsgRouter.execute(InternalMsgRouteParam(relId, GetRelParam))
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

  override def getWalletParam(relId: RelId, destId: DestId): Future[(WalletId, VerKeyStr, Map[ComMethodId, ComMethod])] = {
    for {
      RelationshipResolver.Replies.OutboxParam(walletId, senderVerkey, comMethods)
        <- agentMsgRouter.execute(InternalMsgRouteParam(relId, GetOutboxParam(destId)))
    } yield (walletId, senderVerkey, comMethods)
  }
}