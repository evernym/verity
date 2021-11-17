package com.evernym.verity.actor.testkit.actor

import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.config.AppConfig

import scala.concurrent.ExecutionContext

class MockAgentMsgRouter(val ec: ExecutionContext,
                         mockActorRegionProvider: MockActorRegionProvider = new MockActorRegionProvider{})
                        (implicit val ac: AppConfig, val s: ActorSystem)
  extends AgentMsgRouter(ec) {

  override def getActorTypeToRegions(actorTypeId: Int): ActorRef =
    mockActorRegionProvider.typeToRegions.getOrElse(actorTypeId, super.getActorTypeToRegions(actorTypeId))
}

trait MockActorRegionProvider {
  def typeToRegions: Map[Int, ActorRef] = Map.empty
}