package com.evernym.verity.actor

import akka.actor.ActorSystem
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.container.actor.ActorDriverGenParam
import com.evernym.verity.protocol.engine.ProtocolRegistry

trait ActorContext {
  def system: ActorSystem
  def appConfig: AppConfig

  //TODO: should this be in ActorContext (which was meant to be general for any actor)
  //or shall we move this to AgentActorContext?
  def protocolRegistry: ProtocolRegistry[ActorDriverGenParam]
}