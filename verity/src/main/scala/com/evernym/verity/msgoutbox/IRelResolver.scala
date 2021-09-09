package com.evernym.verity.msgoutbox

import akka.actor.ActorContext
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.msgoutbox.rel_resolver.RelResolver

import scala.concurrent.{ExecutionContext, Future}

trait IRelResolver {
  def resolveOutboxParam(relId: RelId, recipId: RecipId): Future[OutboxIdParam]
}

object RelResolver {
  def apply(executionContext: ExecutionContext, agentMsgRouter: AgentMsgRouter, actorContext: ActorContext): IRelResolver = {
    new RelResolver(executionContext, agentMsgRouter, actorContext)
  }
}