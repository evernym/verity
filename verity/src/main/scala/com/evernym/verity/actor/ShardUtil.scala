package com.evernym.verity.actor

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants._

import scala.concurrent.duration.{Duration, FiniteDuration}

object ShardUtil {

  val forIdentifierEntityIdExtractor: ExtractEntityId  = {
    case ForIdentifier(id, cmd)     => (id, cmd)
  }
  val forTokenEntityIdExtractor: ExtractEntityId  = {
    case ForToken(token, cmd)       => (token, cmd)
  }
  val forUrlMapperEntityIdExtractor: ExtractEntityId  = {
    case ForUrlStore(hashed, cmd)  => (hashed, cmd)
  }

  def forIdentifierShardIdExtractor(sie: ShardIdExtractor): ExtractShardId = {
    case ForIdentifier(id, _)     => sie.getShardId(id)
  }

  def forTokenShardIdExtractor(sie: ShardIdExtractor): ExtractShardId = {
    case ForToken(token, _)       => sie.getShardId(token)
  }

  def forUrlMapperShardIdExtractor(sie: ShardIdExtractor): ExtractShardId = {
    case ForUrlStore(hashed, _)  => sie.getShardId(hashed)
  }
}

case class ShardIdExtractor(appConfig: AppConfig, regionName: String) {
  lazy val normalizedRegionName: String =
    regionName
    .replace("[", "-")
      .replace("]", "-")

  lazy val numberOfShards: Int =
    appConfig
      .getConfigIntOption(s"akka.cluster.region.$normalizedRegionName.total-shards")
      .getOrElse(100)

  def getShardId(entityId: String): String =
    (math.abs(entityId.hashCode) % numberOfShards).toString
}

trait ShardUtil {

  def appConfig: AppConfig

  def createRegion(typeName: String,
                   props: Props,
                   extractShardId: ShardIdExtractor => ExtractShardId = ShardUtil.forIdentifierShardIdExtractor,
                   extractEntityId: ExtractEntityId = ShardUtil.forIdentifierEntityIdExtractor,
                   passivateIdleEntityAfter: FiniteDuration = null
                   )(implicit system: ActorSystem): ActorRef = {
    var clusterSettings = ClusterShardingSettings(system)
    if (passivateIdleEntityAfter != null) {
      clusterSettings = clusterSettings.withPassivateIdleAfter(passivateIdleEntityAfter)
    }
    ClusterSharding(system).start(
      typeName          = typeName,
      entityProps       = props,
      settings          = clusterSettings,
      extractEntityId   = extractEntityId,
      extractShardId    = extractShardId(ShardIdExtractor(appConfig, typeName))
    )
  }
}

trait HasShardRegionNames {
  def userAgentRegionName: String
  def userAgentPairwiseRegionName: String
}

/**
 * unfortunately, for consumer, enterprise and verity, we are using different region names for same type of actor (user agent or user agent pairwise)
 * this is small helper trait to be used by user agent and user agent pairwise actor to find appropriate region names
 */
trait ShardRegionNames extends HasShardRegionNames{
  def appConfig: AppConfig
  lazy val userAgentRegionName: String = USER_AGENT_REGION_ACTOR_NAME
  lazy val userAgentPairwiseRegionName: String = USER_AGENT_PAIRWISE_REGION_ACTOR_NAME
  lazy val activityTrackerRegionName: String = ACTIVITY_TRACKER_REGION_ACTOR_NAME
}

trait ShardRegionCommon extends ShardRegionNames {
  def actorSystem: ActorSystem
  lazy val userAgentRegion: ActorRef = ClusterSharding(actorSystem).shardRegion(userAgentRegionName)
  lazy val userAgentPairwiseRegion: ActorRef = ClusterSharding(actorSystem).shardRegion(userAgentPairwiseRegionName)
  lazy val activityTrackerRegion: ActorRef = ClusterSharding(actorSystem).shardRegion(activityTrackerRegionName)
}

trait ShardRegionFromActorContext extends ShardRegionCommon { this: Actor =>
  def actorSystem: ActorSystem = context.system
}

trait ShardRegionNameFromActorSystem extends ShardRegionCommon {
  def system: ActorSystem
}
