package com.evernym.verity.actor.resourceusagethrottling.tracking

import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.resourceusagethrottling.{BucketId, ResourceName, ResourceType, UsedCount}

//interface for ResourceUsageProvider
trait ResourceUsageProvider {
  def getResourceUsageByBuckets(resourceName: ResourceName): ResourceUsagesByBuckets
  def getAllResources: Map[ResourceName, ResourceBuckets]
}

case class ResourceBuckets(`type`: ResourceType, buckets: Map[BucketId, Bucket])

case class ResourceUsagesByBuckets(usages: Map[BucketId, UsedCount]) extends ActorMessageClass