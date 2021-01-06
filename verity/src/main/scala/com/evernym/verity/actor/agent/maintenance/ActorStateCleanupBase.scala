package com.evernym.verity.actor.agent.maintenance

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.config.CommonConfig

trait ActorStateCleanupBase { this: BasePersistentActor =>

  def isActorStateCleanupEnabled: Boolean =
    appConfig
      .getConfigBooleanOption(CommonConfig.AGENT_ACTOR_STATE_CLEANUP_ENABLED)
      .getOrElse(false)

  //this is internal actor for short period of time and doesn't contain any sensitive data
  override def persistenceEncryptionKey: String = this.getClass.getSimpleName
}


case object ProcessPending extends ActorMessage