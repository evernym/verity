package com.evernym.verity.actor.persistence

import com.evernym.verity.actor.HasAppConfig
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.config.AppConfig


/**
 * base class for all agent persistent actors
 * (namely agency agent, agency agent pairwise, user agent and user agent pairwise)
 */
trait AgentPersistentActor
  extends BasePersistentActor
    with DefaultPersistenceEncryption
    with HasAppConfig {

  implicit def agentActorContext: AgentActorContext
  implicit def appConfig: AppConfig = agentActorContext.appConfig

  def saveSnapshotStateIfAvailable(): Unit
}