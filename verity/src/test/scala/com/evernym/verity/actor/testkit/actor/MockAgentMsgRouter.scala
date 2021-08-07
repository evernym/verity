package com.evernym.verity.actor.testkit.actor

import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.config.AppConfig

import scala.concurrent.ExecutionContext

class MockAgentMsgRouter(val ec: ExecutionContext, actorTypeToRegionsMapping: Map[Int, ActorRef]=Map.empty)
                        (implicit val ac: AppConfig, val s: ActorSystem)
  extends AgentMsgRouter(ec) {

  require(actorTypeToRegionsMapping != null, "actorTypeToRegionsMapping can't be null")

  override def getActorTypeToRegions(actorTypeId: Int): ActorRef =
    actorTypeToRegionsMapping.getOrElse(actorTypeId, super.getActorTypeToRegions(actorTypeId))
}