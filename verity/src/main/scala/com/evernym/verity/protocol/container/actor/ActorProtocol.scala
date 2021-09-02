package com.evernym.verity.protocol.container.actor

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.protocol.engine.{ProtoDef, ProtoRef}

import scala.concurrent.ExecutionContext

/**
  * A Protocol representation in an actor system
  */

object ActorProtocol {

  def apply(protoDef: ProtoDef) = new ActorProtocol(protoDef)

  def buildTypeName(protoDef: ProtoDef): String = {
    buildTypeName(protoDef.protoRef)
  }

  def buildTypeName(protoRef: ProtoRef): String = {
    s"${protoRef.msgFamilyName}-${protoRef.msgFamilyVersion}-protocol"
  }
}

class ActorProtocol(val protoDef: ProtoDef) {

  def typeName: String = ActorProtocol.buildTypeName(protoDef)
  def region(implicit system: ActorSystem): ActorRef = ClusterSharding(system).shardRegion(typeName)

  def props(agentActorContext: AgentActorContext, executionContext: ExecutionContext): Props = {
    Props(classOf[ActorProtocolContainer[_, _, _, _, _, _, _]], agentActorContext, protoDef, executionContext)
  }

}
