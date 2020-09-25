package com.evernym.verity.actor.testkit.actor

import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.config.AppConfig

class MockAgentMsgRouter(val actorTypeToRegionsMapping: Map[Int, ActorRef]=Map.empty)(implicit val ac: AppConfig, val s: ActorSystem)
  extends AgentMsgRouter() {

  override def getActorTypeToRegions(actorTypeId: Int): ActorRef =
    actorTypeToRegionsMapping.getOrElse(actorTypeId, super.getActorTypeToRegions(actorTypeId))
}