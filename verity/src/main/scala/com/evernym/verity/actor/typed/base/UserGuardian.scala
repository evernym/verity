package com.evernym.verity.actor.typed.base

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.AgentActorContext

import scala.concurrent.ExecutionContext


// top level typed user guardian actor
// all user typed actors should be children of this one
object UserGuardian {

  sealed trait Cmd extends ActorMessage

  def apply(agentActorContext: AgentActorContext, executionContext: ExecutionContext): Behavior[Cmd] = {
    Behaviors.setup { ctx =>
      initialized(ctx)
    }
  }

  def initialized(implicit ctx: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
    case _ =>
      Behaviors.same
  }
}