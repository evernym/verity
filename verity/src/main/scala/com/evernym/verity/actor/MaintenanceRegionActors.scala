package com.evernym.verity.actor

import com.evernym.verity.actor.agent.maintenance.ActorStateCleanupExecutor
import com.evernym.verity.constants.ActorNameConstants.ACTOR_STATE_CLEANUP_EXECUTOR

trait MaintenanceRegionActors { this: Platform =>
  val actorStateCleanupExecutor = createPersistentRegion(
    ACTOR_STATE_CLEANUP_EXECUTOR,
    ActorStateCleanupExecutor.props(appConfig, agentActorContext.agentMsgRouter))
}
