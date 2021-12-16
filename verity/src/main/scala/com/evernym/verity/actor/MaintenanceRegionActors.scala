package com.evernym.verity.actor

import java.util.concurrent.TimeUnit
import com.evernym.verity.actor.agent.maintenance.ActorStateCleanupExecutor
import com.evernym.verity.actor.maintenance.v1tov2migration.ConnectionMigrator
import com.evernym.verity.config.ConfigConstants.{NON_PERSISTENT_ACTOR_BASE, PERSISTENT_ACTOR_BASE}
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.constants.ActorNameConstants.ACTOR_STATE_CLEANUP_EXECUTOR

import scala.concurrent.duration.FiniteDuration

trait MaintenanceRegionActors { this: Platform =>
  val actorStateCleanupExecutor = createPersistentRegion(
    ACTOR_STATE_CLEANUP_EXECUTOR,
    ActorStateCleanupExecutor.props(
      appConfig,
      agentActorContext,
      this.executionContextProvider.futureExecutionContext
    ),
    passivateIdleEntityAfter = Some(FiniteDuration(
      ConfigUtil.getReceiveTimeout(
        appConfig,
        ActorStateCleanupExecutor.defaultPassivationTimeout,
        PERSISTENT_ACTOR_BASE,
        ACTOR_STATE_CLEANUP_EXECUTOR,
        null
      ).toSeconds,
      TimeUnit.SECONDS
    ))
  )

  val connectionMigrator = createNonPersistentRegion(
    "ConnectionMigrator",
    ConnectionMigrator.props(
      appConfig,
      agentActorContext.walletAPI,
      agentActorContext.agentMsgRouter,
      this.executionContextProvider.futureExecutionContext
    ),
    passivateIdleEntityAfter = Some(FiniteDuration(
      ConfigUtil.getReceiveTimeout(
        appConfig,
        300,
        NON_PERSISTENT_ACTOR_BASE,
        "ConnectionMigrator",
        null
      ).toSeconds,
      TimeUnit.SECONDS
    ))
  )
}
