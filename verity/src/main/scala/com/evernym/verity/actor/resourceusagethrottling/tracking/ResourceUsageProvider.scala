package com.evernym.verity.actor.resourceusagethrottling.tracking

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.resourceusagethrottling.{BucketId, ResourceName, ResourceType, UsedCount}

//interface for ResourceUsageProvider
trait ResourceUsageProvider {
  def getResourceUsageByBuckets(resourceName: ResourceName): ResourceUsagesByBuckets
  def getAllResourceBuckets: Map[ResourceName, ResourceBuckets]
}

case class ResourceBuckets(`type`: ResourceType, buckets: Map[BucketId, Bucket])

case class ResourceUsagesByBuckets(usages: Map[BucketId, UsedCount]) extends ActorMessage