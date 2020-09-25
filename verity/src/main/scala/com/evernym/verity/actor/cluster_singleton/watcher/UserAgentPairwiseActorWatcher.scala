package com.evernym.verity.actor.cluster_singleton.watcher

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.actor.itemmanager.ItemCommonType.ItemType
import com.evernym.verity.actor.itemmanager.ItemConfigManager
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.agent.user.CheckRetryJobScheduled
import com.evernym.verity.config.AppConfig


object UserAgentPairwiseActorWatcher {
  val name: String = USER_AGENT_PAIRWISE_ACTOR_WATCHER
  def props(entityRegionActorName: String, config: AppConfig): Props =
    Props(classOf[UserAgentPairwiseActorWatcher], entityRegionActorName, config)

  def itemManagerEntityId(appConfig: AppConfig): String = {
    ItemConfigManager.entityId(appConfig, "uap-actor-watcher")
  }
}

class UserAgentPairwiseActorWatcher(val entityRegionActorName: String)(implicit val appConfig: AppConfig)
  extends WatcherBase {

  override lazy val itemType: ItemType = UserAgentPairwiseActorWatcher.name

  override lazy val itemManagerEntityId: String = UserAgentPairwiseActorWatcher.itemManagerEntityId(appConfig)

  override lazy val migrateItemsToNextLinkedContainer: Boolean = true

  override lazy val migrateItemsToLatestVersionedContainers: Boolean = false

  lazy val agentPairwiseRegion: ActorRef = ClusterSharding(context.system).shardRegion(entityRegionActorName)

  override def sendMsgToWatchedItem(entityId: String): Unit = {
    agentPairwiseRegion ! ForIdentifier(entityId, CheckRetryJobScheduled)
  }

  override def activeRegisteredItemMetricsName: String = "as.akka.actor.user.agent.pairwise.retry.active.count"
  override def pendingActiveRegisteredItemMetricsName: String = "as.akka.actor.user.agent.pairwise.retry.pending.count"

}

