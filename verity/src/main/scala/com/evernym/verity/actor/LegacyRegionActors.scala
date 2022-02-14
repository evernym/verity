package com.evernym.verity.actor

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import com.evernym.verity.actor.agent.msgrouter.legacy.LegacyAgentRouteStore
import com.evernym.verity.actor.agent.user.{UserAgent, UserAgentPairwise}
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.config.ConfigConstants.{AKKA_SHARDING_REGION_NAME_USER_AGENT, AKKA_SHARDING_REGION_NAME_USER_AGENT_PAIRWISE, USER_AGENT_ACTOR_DISPATCHER_NAME, USER_AGENT_PAIRWISE_ACTOR_DISPATCHER_NAME, PERSISTENT_ACTOR_BASE}
import com.evernym.verity.constants.ActorNameConstants.{LEGACY_AGENT_ROUTE_STORE_REGION_ACTOR_NAME, USER_AGENT_PAIRWISE_REGION_ACTOR_NAME, USER_AGENT_REGION_ACTOR_NAME}

import scala.concurrent.duration.FiniteDuration

trait LegacyRegionActors extends LegacyRegionNames { this: Platform =>

  //legacy routing agent store region actor
  val legacyAgentRouteStoreRegion: ActorRef =
    createPersistentRegion(
      LEGACY_AGENT_ROUTE_STORE_REGION_ACTOR_NAME,
      LegacyAgentRouteStore.props(agentActorContext.agentMsgRouter, executionContextProvider.futureExecutionContext),
      passivateIdleEntityAfter = Some(FiniteDuration(
        ConfigUtil.getReceiveTimeout(
          appConfig,
          LegacyAgentRouteStore.defaultPassivationTimeout,
          PERSISTENT_ACTOR_BASE,
          LEGACY_AGENT_ROUTE_STORE_REGION_ACTOR_NAME,
          null
        ).toSeconds,
        TimeUnit.SECONDS
      ))

    )

  //region actor for legacy user agent actors
  if (userAgentRegionName != USER_AGENT_REGION_ACTOR_NAME) {
    createPersistentRegion(
      userAgentRegionName, //this is the main change compared to corresponding standard region actors
      buildProp(
        Props(
          new UserAgent(
            agentActorContext,
            collectionsMetricsCollector,
            this.executionContextProvider.futureExecutionContext
          )
        ),
        Option(USER_AGENT_ACTOR_DISPATCHER_NAME)
      ),
      passivateIdleEntityAfter = Some(FiniteDuration(
        ConfigUtil.getReceiveTimeout(
          appConfig,
          UserAgent.defaultPassivationTimeout,
          PERSISTENT_ACTOR_BASE,
          userAgentRegionName,
          null
        ).toSeconds,
        TimeUnit.SECONDS
      ))
    )
  }

  //region actor for legacy user agent pairwise actors
  if (userAgentPairwiseRegionName != USER_AGENT_PAIRWISE_REGION_ACTOR_NAME) {
    createPersistentRegion(
      userAgentPairwiseRegionName, //this is the main change compared to corresponding standard region actors
      buildProp(
        Props(
          new UserAgentPairwise(
            agentActorContext,
            collectionsMetricsCollector,
            this.executionContextProvider.futureExecutionContext
          )
        ),
        Option(USER_AGENT_PAIRWISE_ACTOR_DISPATCHER_NAME)
      ),
      passivateIdleEntityAfter = Some(FiniteDuration(
        ConfigUtil.getReceiveTimeout(
          appConfig,
          UserAgentPairwise.defaultPassivationTimeout,
          PERSISTENT_ACTOR_BASE,
          userAgentPairwiseRegionName,
          null
        ).toSeconds,
        TimeUnit.SECONDS
      ))
    )
  }
}

trait HasLegacyRegionNames {
  def appConfig: AppConfig
  lazy val LEGACY_USER_AGENT_REGION_ACTOR_NAME: String = appConfig.getStringReq(AKKA_SHARDING_REGION_NAME_USER_AGENT)
  lazy val LEGACY_USER_AGENT_PAIRWISE_REGION_ACTOR_NAME: String = appConfig.getStringReq(AKKA_SHARDING_REGION_NAME_USER_AGENT_PAIRWISE)
}


trait LegacyRegionNames extends HasShardRegionNames with HasLegacyRegionNames{
  def appConfig: AppConfig
  override lazy val userAgentRegionName: String = LEGACY_USER_AGENT_REGION_ACTOR_NAME
  override lazy val userAgentPairwiseRegionName: String = LEGACY_USER_AGENT_PAIRWISE_REGION_ACTOR_NAME
}
