package com.evernym.verity.actor.resourceusagethrottling.tracking

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.actor.resourceusagethrottling._
import com.evernym.verity.http.route_handlers.restricted.{ResourceUsageCounterDetail, UpdateResourcesUsageCounter}
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageRuleHelper
import com.evernym.verity.util.Util.logger

trait ResourceUsageCommon {

  def system: ActorSystem

  protected lazy val resourceUsageTrackerRegion: ActorRef =
    ClusterSharding(system).shardRegion(RESOURCE_USAGE_TRACKER_REGION_ACTOR_NAME)

  protected def addUserResourceUsage(resourceType: ResourceType,
                                     resourceName: ResourceName,
                                     ipAddressOpt: Option[IpAddress],
                                     userIdOpt: Option[UserId],
                                     sendBackAck: Boolean=false): Unit = {
    ResourceUsageTracker.addUserResourceUsage(resourceType,
      resourceName, ipAddressOpt, userIdOpt, sendBackAck)(resourceUsageTrackerRegion)
  }

  protected def resetResourceUsageCounts(entityId: EntityId, resourceName: ResourceName): Unit = {
    // Set resource usage counts to 0 for each resource (entityId, resourceName)
    // Get bucket IDs for all buckets associated with resourceName
    val buckets: Set[Int] = Set(
      ResourceUsageRuleHelper.getResourceUsageRule(entityId, RESOURCE_TYPE_ENDPOINT, resourceName),
      ResourceUsageRuleHelper.getResourceUsageRule(entityId, RESOURCE_TYPE_MESSAGE, resourceName)
    ).flatten.flatMap { rule => rule.bucketRules.keys }

    logger.debug(s"Reset $resourceName resource usage counts for buckets: ${buckets mkString ", "}")
    val counterDetails: List[ResourceUsageCounterDetail] = buckets.map {
      b => ResourceUsageCounterDetail(resourceName, b, None)
    }.toList

    ResourceUsageTracker.sendToResourceUsageTracker(entityId,
      UpdateResourcesUsageCounter(counterDetails))(resourceUsageTrackerRegion)
  }
}
