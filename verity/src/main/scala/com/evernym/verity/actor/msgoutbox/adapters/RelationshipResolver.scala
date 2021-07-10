package com.evernym.verity.actor.msgoutbox.adapters

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.msgoutbox.ComMethod
import com.evernym.verity.actor.msgoutbox.adapters.RelationshipResolver.Reply
import com.evernym.verity.actor.msgoutbox.outbox.{DestId, ParticipantId}
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT

//ephemeral actor (one for a relationship in one node)
object RelationshipResolver {

  sealed trait Cmd
  object Commands {
    case class SendOutboxParam(forDestId: DestId, replyTo: ActorRef[Reply]) extends Cmd
  }

  trait Reply
  object Replies {
    case class OutboxParam(walletId: String,
                           senderVerKey: String,
                           comMethods: Map[String, ComMethod]) extends Reply {
      if (comMethods.count(_._2.typ == COM_METHOD_TYPE_HTTP_ENDPOINT) > 1) {
        throw new RuntimeException("one outbox can have max one http com method")
      }
    }
  }

  def apply(relParticipantId: ParticipantId,
            agentMsgRouter: AgentMsgRouter): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      initialized(relParticipantId)(agentMsgRouter, actorContext)
    }
  }

  private def initialized(relParticipantId: ParticipantId)
                         (implicit agentMsgRouter: AgentMsgRouter,
                          actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {
    case Commands.SendOutboxParam(forDestId, replyTo: ActorRef[Reply]) =>
      agentMsgRouter.forward(
        (InternalMsgRouteParam(relParticipantId, SendOutboxParam(forDestId, replyTo)),
        akka.actor.ActorRef.noSender)
      )
      Behaviors.same
  }
}

case class SendOutboxParam(forDestId: DestId, replyTo: ActorRef[Reply]) extends ActorMessage