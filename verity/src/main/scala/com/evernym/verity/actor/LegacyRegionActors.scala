package com.evernym.verity.actor

import akka.actor.Props
import com.evernym.verity.actor.agent.user.{UserAgent, UserAgentPairwise}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.{USER_AGENT_ACTOR_DISPATCHER_NAME, USER_AGENT_PAIRWISE_ACTOR_DISPATCHER_NAME, AKKA_SHARDING_REGION_NAME_USER_AGENT, AKKA_SHARDING_REGION_NAME_USER_AGENT_PAIRWISE}
import com.evernym.verity.constants.ActorNameConstants.{USER_AGENT_REGION_ACTOR_NAME, USER_AGENT_PAIRWISE_REGION_ACTOR_NAME}

trait LegacyRegionActors extends LegacyRegionNames { this: Platform =>

  //region actor for legacy user agent actors
  if (userAgentRegionName != USER_AGENT_REGION_ACTOR_NAME) {
    createPersistentRegion(
      userAgentRegionName, //this is the main change compared to corresponding standard region actors
      buildProp(Props(new UserAgent(agentActorContext, collectionsMetricsCollector)), Option(USER_AGENT_ACTOR_DISPATCHER_NAME)))
  }

  //region actor for legacy user agent pairwise actors
  if (userAgentPairwiseRegionName != USER_AGENT_PAIRWISE_REGION_ACTOR_NAME) {
    createPersistentRegion(
      userAgentPairwiseRegionName, //this is the main change compared to corresponding standard region actors
      buildProp(Props(new UserAgentPairwise(agentActorContext, collectionsMetricsCollector)), Option(USER_AGENT_PAIRWISE_ACTOR_DISPATCHER_NAME)))
  }
}

trait HasLegacyRegionNames {
  def appConfig: AppConfig
  lazy val LEGACY_USER_AGENT_REGION_ACTOR_NAME: String = appConfig.getConfigStringReq(AKKA_SHARDING_REGION_NAME_USER_AGENT)
  lazy val LEGACY_USER_AGENT_PAIRWISE_REGION_ACTOR_NAME: String = appConfig.getConfigStringReq(AKKA_SHARDING_REGION_NAME_USER_AGENT_PAIRWISE)
}


trait LegacyRegionNames extends HasShardRegionNames with HasLegacyRegionNames{
  def appConfig: AppConfig
  override lazy val userAgentRegionName: String = LEGACY_USER_AGENT_REGION_ACTOR_NAME
  override lazy val userAgentPairwiseRegionName: String = LEGACY_USER_AGENT_PAIRWISE_REGION_ACTOR_NAME
}
