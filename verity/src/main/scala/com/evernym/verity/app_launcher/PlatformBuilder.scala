package com.evernym.verity.app_launcher

import akka.actor.ActorSystem
import com.evernym.verity.actor.{Platform, PlatformServices}
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.config.{AppConfig, AppConfigWrapper}


object PlatformBuilder {
  def build(agentActorContext: Option[AgentActorContext]=None): Platform = {
    new Platform(agentActorContext.getOrElse(new DefaultAgentActorContext()), PlatformServices)
  }
}

class DefaultAgentActorContext(val appConfig: AppConfig = AppConfigWrapper) extends AgentActorContext {
  override implicit lazy val system: ActorSystem = createActorSystem()
}