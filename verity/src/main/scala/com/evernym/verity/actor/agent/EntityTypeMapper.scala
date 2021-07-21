package com.evernym.verity.actor.agent

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.{AKKA_SHARDING_REGION_NAME_USER_AGENT, AKKA_SHARDING_REGION_NAME_USER_AGENT_PAIRWISE}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.msgoutbox.outbox.Outbox

object EntityTypeMapper {

  private val entityTypeMapping = Map (
    ACTOR_TYPE_AGENCY_AGENT_ACTOR                -> AGENCY_AGENT_REGION_ACTOR_NAME,
    ACTOR_TYPE_AGENCY_AGENT_PAIRWISE_ACTOR       -> AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
    ACTOR_TYPE_USER_AGENT_ACTOR                  -> USER_AGENT_REGION_ACTOR_NAME,
    ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR         -> USER_AGENT_PAIRWISE_REGION_ACTOR_NAME,
    ACTOR_TYPE_OUTBOX_ACTOR                      -> Outbox.TypeKey.name
  )

  def buildEntityTypeMappings(appConfig: AppConfig): Map[Int, AttrValue] = {
    val legacyEntityMapping = Map (
      LEGACY_ACTOR_TYPE_USER_AGENT_ACTOR          -> appConfig.getStringReq(AKKA_SHARDING_REGION_NAME_USER_AGENT),
      LEGACY_ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR -> appConfig.getStringReq(AKKA_SHARDING_REGION_NAME_USER_AGENT_PAIRWISE)
    )
    entityTypeMapping ++ legacyEntityMapping
  }

  def buildRegionMappings(appConfig: AppConfig, actorSystem: ActorSystem): Map[Int, ActorRef] = {
    val allMapping = buildEntityTypeMappings(appConfig)

    allMapping.map { e =>
      e._1 -> ClusterSharding(actorSystem).shardRegion(e._2)
    }
  }

}
