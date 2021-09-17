package com.evernym.verity.msgoutbox

import akka.util.Timeout
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.msgoutbox.rel_resolver.RelResolverImpl

import scala.concurrent.{ExecutionContext, Future}

trait RelResolver {
  def resolveOutboxParam(relId: RelId, recipId: RecipId): Future[OutboxIdParam]
  def getWalletParam(relId: RelId, destId: DestId): Future[(WalletId, VerKeyStr, Map[ComMethodId, ComMethod])]
}

object RelResolver {
  def apply(executionContext: ExecutionContext, agentMsgRouter: AgentMsgRouter, timeout: Option[Timeout] = None): RelResolver = {
    new RelResolverImpl(timeout)(executionContext, agentMsgRouter)
  }
}