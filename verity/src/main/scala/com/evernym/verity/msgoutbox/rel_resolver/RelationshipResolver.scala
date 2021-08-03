package com.evernym.verity.msgoutbox.rel_resolver

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.relationship.Relationship
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Commands.{OutboxParamResp, RelParamResp}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Replies.{OutboxParam, RelParam}
import com.evernym.verity.msgoutbox.{ComMethod, ComMethodId, DestId, RelId, WalletId}
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.did.VerKey

//ephemeral actor (sharded)
object RelationshipResolver {

  trait Cmd extends ActorMessage
  object Commands {
    case class SendOutboxParam(relId: RelId, destId: DestId, replyTo: ActorRef[Reply]) extends Cmd
    case class GetRelParam(relId: RelId, replyTo: ActorRef[Reply]) extends Cmd

    case class OutboxParamResp(walletId: WalletId,
                               senderVerKey: VerKey,
                               comMethods: Map[ComMethodId, ComMethod]) extends Cmd {
      if (comMethods.count(_._2.typ == COM_METHOD_TYPE_HTTP_ENDPOINT) > 1) {
        throw new RuntimeException("one outbox can have max one http com method")
      }
    }

    case class RelParamResp(selfRelId: RelId, relationship: Option[Relationship]) extends Cmd
  }

  trait Reply extends ActorMessage
  object Replies {
    case class OutboxParam(walletId: WalletId,
                           senderVerKey: VerKey,
                           comMethods: Map[ComMethodId, ComMethod]) extends Reply {
      if (comMethods.count(_._2.typ == COM_METHOD_TYPE_HTTP_ENDPOINT) > 1) {
        throw new RuntimeException("one outbox can have max one http com method")
      }
    }

    case class RelParam(selfRelId: RelId, relationship: Option[Relationship]) extends Reply
  }

  def apply(agentMsgRouter: AgentMsgRouter): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      Behaviors.withStash(10) { buffer =>      //TODO: finalize this
        initialized(actorContext, buffer, agentMsgRouter)
      }
    }
  }

  private def initialized(implicit actorContext: ActorContext[Cmd],
                          buffer: StashBuffer[Cmd],
                          agentMsgRouter: AgentMsgRouter): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {
    case Commands.SendOutboxParam(relId, destId, replyTo: ActorRef[Reply]) =>
      agentMsgRouter.forward((InternalMsgRouteParam(relId, GetOutboxParam(destId)), actorContext.self.toClassic))
      waitingForGetOutboxParam(replyTo)

    case Commands.GetRelParam(relId, replyTo: ActorRef[Reply]) =>
      agentMsgRouter.forward((InternalMsgRouteParam(relId, GetRelParam), actorContext.self.toClassic))
      waitingForGetRelParam(replyTo)
  }

  private def waitingForGetOutboxParam(replyTo: ActorRef[Reply]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {
    case OutboxParamResp(walletId, senderVerKey, comMethods) =>
      replyTo ! OutboxParam(walletId, senderVerKey, comMethods)
      Behaviors.stopped
  }

  private def waitingForGetRelParam(replyTo: ActorRef[Reply]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {
    case RelParamResp(selfRelId, relationship) =>
      replyTo ! RelParam(selfRelId, relationship)
      Behaviors.stopped
  }
}

trait RelCmds extends ActorMessage
case class GetOutboxParam(destId: DestId) extends RelCmds
case object GetRelParam extends RelCmds
