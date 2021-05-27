package com.evernym.verity.actor

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import com.evernym.verity.actor.persistence.SupervisorUtil.{logger, supervisorProps}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.ActorNameConstants._

import scala.concurrent.duration.FiniteDuration

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
    math.abs(entityId.hashCode % numberOfShards).toString
}

trait ShardUtil {

  def appConfig: AppConfig

  private def createRegionBase(typeName: String,
                               props: Props,
                               extractShardId: ShardIdExtractor => ExtractShardId = ShardUtil.forIdentifierShardIdExtractor,
                               extractEntityId: ExtractEntityId = ShardUtil.forIdentifierEntityIdExtractor,
                               passivateIdleEntityAfter: Option[FiniteDuration] = None
                               )(implicit system: ActorSystem): ActorRef = {
    val defaultClusterSetting = ClusterShardingSettings(system)

    val finalClusterSettings = passivateIdleEntityAfter match {
      case Some(pt) => defaultClusterSetting.withPassivateIdleAfter(pt)
      case None     => defaultClusterSetting
    }
    ClusterSharding(system).start(
      typeName          = typeName,
      entityProps       = props,
      settings          = finalClusterSettings,
      extractEntityId   = extractEntityId,
      extractShardId    = extractShardId(ShardIdExtractor(appConfig, typeName))
    )
  }

  def finalActorProps(entityCategory: String, typeName: String, actorProps: Props): Props = {
    //NOTE: for any persistent shard region actors,
    // once we are ready (tested/finalized) to use backoff supervisor strategy
    // we need to change only this function to return backoff supervisor actor props
    // instead of given 'actorProps'

    //NOTE: There will be few other non sharded persistent actors which
    // we'll have to take care of separately.

    supervisorProps(
      appConfig,
      entityCategory,
      typeName,
      actorProps
    )
    .getOrElse{
      logger.debug(s"Supervisor was not defined for this props - entityCategory: $entityCategory - typeName: $typeName")
      actorProps
    }
  }

  def createPersistentRegion(typeName: String,
                             props: Props,
                             extractShardId: ShardIdExtractor => ExtractShardId = ShardUtil.forIdentifierShardIdExtractor,
                             extractEntityId: ExtractEntityId = ShardUtil.forIdentifierEntityIdExtractor,
                             passivateIdleEntityAfter: Option[FiniteDuration] = None
                            )(implicit system: ActorSystem): ActorRef = {

    createRegionBase(
      typeName,
      finalActorProps(PERSISTENT_ACTOR_BASE, typeName, props),
      extractShardId,
      extractEntityId,
      passivateIdleEntityAfter
    )
  }

  def createProtoActorRegion(typeName: String,
                             props: Props,
                             extractShardId: ShardIdExtractor => ExtractShardId = ShardUtil.forIdentifierShardIdExtractor,
                             extractEntityId: ExtractEntityId = ShardUtil.forIdentifierEntityIdExtractor,
                             passivateIdleEntityAfter: Option[FiniteDuration] = None
                            )(implicit system: ActorSystem): ActorRef = {
    createRegionBase(
      typeName,
      finalActorProps(PERSISTENT_PROTOCOL_CONTAINER, typeName, props),
      extractShardId,
      extractEntityId,
      passivateIdleEntityAfter
    )
  }

  def createNonPersistentRegion(typeName: String,
                                props: Props,
                                extractShardId: ShardIdExtractor => ExtractShardId = ShardUtil.forIdentifierShardIdExtractor,
                                extractEntityId: ExtractEntityId = ShardUtil.forIdentifierEntityIdExtractor,
                                passivateIdleEntityAfter: Option[FiniteDuration] = None
                               )(implicit system: ActorSystem): ActorRef = {
    createRegionBase(
      typeName,
      props,
      extractShardId,
      extractEntityId,
      passivateIdleEntityAfter
    )
  }
}

object UserActorUtil {

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
  lazy val walletActorRegionName: String = WALLET_REGION_ACTOR_NAME
}

trait ShardRegionCommon extends ShardRegionNames {
  def system: ActorSystem
  lazy val userAgentRegion: ActorRef = ClusterSharding(system).shardRegion(userAgentRegionName)
  lazy val userAgentPairwiseRegion: ActorRef = ClusterSharding(system).shardRegion(userAgentPairwiseRegionName)
  lazy val activityTrackerRegion: ActorRef = ClusterSharding(system).shardRegion(activityTrackerRegionName)
}

trait ShardRegionFromActorContext extends ShardRegionCommon { this: Actor =>
  def system: ActorSystem = context.system
}

trait ShardRegionNameFromActorSystem extends ShardRegionCommon {
  def system: ActorSystem
}
