package com.evernym.verity.actor.persistence

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.config.AppConfig

trait SingletonPersistentAgentActorBase extends SingletonPersistentActorBase {

  def agentActorContext: AgentActorContext
  def appConfig: AppConfig = agentActorContext.appConfig

  def secretConfigKeyName: String
  override lazy val persistenceEncryptionKey: String = agentActorContext.appConfig.getConfigStringReq(secretConfigKeyName)
}
