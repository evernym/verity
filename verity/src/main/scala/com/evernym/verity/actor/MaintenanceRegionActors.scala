package com.evernym.verity.actor

import com.evernym.verity.actor.agent.msgrouter.LegacyRouteUpdater
import com.evernym.verity.constants.ActorNameConstants.LEGACY_ROUTE_UPDATER

trait MaintenanceRegionActors { this: Platform =>
  val legacyRouteUpdater = createRegion(LEGACY_ROUTE_UPDATER, LegacyRouteUpdater.props(appConfig, agentActorContext.agentMsgRouter))
}
