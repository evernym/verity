package com.evernym.verity.http.route_handlers.restricted.models

import com.evernym.verity.actor.ActorMessage
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

case class UpdateViolationDetail(msgType: String,
                                 @JsonDeserialize(contentAs = classOf[Long]) period: Option[Long],
                                 allResources: Option[String])

case class ResourceUsageLimitDetail(resourceName: String, bucketId: Int,
                                    newLimit: Option[Int] = None, addToCurrentUsedCount: Option[Int] = None) {
  require(!(newLimit.isEmpty && addToCurrentUsedCount.isEmpty),
    "one and only one of these should be specified: 'newLimit' or 'addToCurrentUsedCount'")
}

case class UpdateResourcesUsageLimit(resourceUsageLimits: List[ResourceUsageLimitDetail])

case class ResourceUsageCounterDetail(resourceName: String, bucketId: Int, newCount: Option[Int])

case class UpdateResourcesUsageCounter(resourceUsageCounters: List[ResourceUsageCounterDetail]) extends ActorMessage
