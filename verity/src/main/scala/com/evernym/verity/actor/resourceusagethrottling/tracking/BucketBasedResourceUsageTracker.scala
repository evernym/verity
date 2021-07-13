package com.evernym.verity.actor.resourceusagethrottling.tracking

import java.time.ZonedDateTime
import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor._
import com.evernym.verity.actor.resourceusagethrottling.{tracking, _}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.actor.resourceusagethrottling.helper.{BucketRule, ResourceUsageRule, ResourceUsageRuleHelper}
import com.evernym.verity.metrics.{InternalSpan, MetricsWriter}
import com.evernym.verity.util.TimeZoneUtil._
import com.typesafe.scalalogging.Logger

//below is custom implementation of 'ResourceUsageProvider'
// which later on can be replaced with better one
class BucketBasedResourceUsageTracker extends ResourceUsageProvider {

  val logger: Logger = getLoggerByClass(classOf[BucketBasedResourceUsageTracker])

  var resourceUsages = Map.empty[ResourceName, ResourceBuckets]
  var modifiedResourceUsageLimit = Map.empty[ResourceName, Map[BucketId, UsageLimit]]
  var modifiedResourceUsedCounter = Map.empty[ResourceName, Map[BucketId, UsedCount]]

  /**
   * cleanup un-tracked buckets
   * for example, if there was a bucket for '300' second being tracked earlier
   * but later on, that bucket is removed (via configuration) so that won't be tracked any more
   * and should be removed from the tracking state
   * @param resourceName resource name
   * @param activeBucketIds active bucket ids (any other bucket id should be cleaned up)
   */
  def cleanupStaleData(resourceName: ResourceName, activeBucketIds: Set[BucketId]): Unit = {
    resourceUsages.get(resourceName).foreach { rbs =>
      val newBds = rbs.buckets.filter(bd => activeBucketIds.contains(bd._1))
      resourceUsages = resourceUsages + (resourceName -> ResourceBuckets(rbs.`type`, newBds))
    }
  }

  def getCustomLimit(resourceName: ResourceName, bucketId: BucketId): Option[UsageLimit]= {
    modifiedResourceUsageLimit.get(resourceName).flatMap(_.get(bucketId))
  }

  def updateResourceUsageLimit(rulu: ResourceUsageLimitUpdated): Unit = {
    val newBucketLimit = Map(rulu.bucketId -> rulu.newLimit)
    val curBucketLimits = modifiedResourceUsageLimit.getOrElse(rulu.resourceName, Map.empty)
    modifiedResourceUsageLimit = modifiedResourceUsageLimit + (rulu.resourceName -> (curBucketLimits ++ newBucketLimit))
  }

  def removeResetCounter(resourceName: ResourceName, bucketId: BucketId): Unit = {
    modifiedResourceUsedCounter.get(resourceName).flatMap(_.get(bucketId)).foreach { _ =>
      val currentModifiedUsageCounters = modifiedResourceUsedCounter.getOrElse(resourceName, Map.empty)
      modifiedResourceUsedCounter = modifiedResourceUsedCounter + (resourceName -> currentModifiedUsageCounters.filter(_._1 != bucketId))
    }
  }

  def applyResetUsedCounterToCurrentUsages(rucu: ResourceUsageCounterUpdated): Unit = {
    resourceUsages.get(rucu.resourceName).foreach { rbs =>
      rbs.buckets.filter(_._1 == rucu.bucketId).foreach { case (bId, bucket) =>
        val modifiedResourceBuckets = rbs.copy(buckets = rbs.buckets + (bId -> bucket.copy(usedCount = rucu.newCounter)))
        resourceUsages = resourceUsages + (rucu.resourceName -> modifiedResourceBuckets)
        removeResetCounter(rucu.resourceName, rucu.bucketId)
      }
    }
  }

  def updateResourceUsageCounter(rucu: ResourceUsageCounterUpdated): Unit = {
    val newBucketCounter = Map(rucu.bucketId -> rucu.newCounter)
    val curBucketCounters = modifiedResourceUsedCounter.getOrElse(rucu.resourceName, Map.empty)
    modifiedResourceUsedCounter = modifiedResourceUsedCounter + (rucu.resourceName -> (curBucketCounters ++ newBucketCounter))
    applyResetUsedCounterToCurrentUsages(rucu)
  }

  def getSnapshotState: ResourceUsageState = {
    val isPersistAllUsageState = ResourceUsageRuleHelper.resourceUsageRules.persistAllBucketUsages

    val resourceBuckets =
      resourceUsages.map { ru =>
        val buckets = ru._2.buckets.filter(b => isPersistAllUsageState || b._2.persistUsages).map { b =>
          val startDateTimeMillis: Long = b._2.startDateTime.map(getMillisFromZonedDateTime).getOrElse(-1)
          val endDateTimeMillis: Long = b._2.endDateTime.map(getMillisFromZonedDateTime).getOrElse(-1)
          BucketDetail(b._1, b._2.usedCount, startDateTimeMillis, endDateTimeMillis)
        }.toSeq
        ResourceBucket(ru._1, ru._2.`type`, buckets)
      }.toSeq
    ResourceUsageState(resourceBuckets)
  }

  def updateWithSnapshotState(snapshotState: ResourceUsageState): Unit = {
    snapshotState.resourceBuckets.foreach { rb =>
      val resourceBuckets = rb.buckets.map { bd =>
        val startDateTime = if (bd.startDateTime == -1) None else Option(getZonedDateTimeFromMillis(bd.startDateTime)(UTCZoneId))
        val endDateTime = if (bd.endDateTime == -1) None else Option(getZonedDateTimeFromMillis(bd.endDateTime)(UTCZoneId))
        bd.id -> Bucket(bd.usedCount, persistUsages=true, startDateTime, endDateTime)
      }.toMap
      resourceUsages = resourceUsages + (rb.name -> tracking.ResourceBuckets(rb.`type`, resourceBuckets))
    }
  }

  def createNewBucket(bId: BucketId, bucketRule: BucketRule, curDateTime: ZonedDateTime): Bucket = {
    val bucketStartDateOpt = if (bId == BUCKET_ID_INDEFINITE_TIME) None else Option(curDateTime)
    val bucketEndDateOpt = if (bId == BUCKET_ID_INDEFINITE_TIME) None else Option(curDateTime.plusSeconds(bId))
    Bucket(0, bucketRule.persistUsageState, bucketStartDateOpt, bucketEndDateOpt)
  }

  def getExistingOrNewBucket(bId: BucketId, bucketRule: BucketRule,
                             curDateTime: ZonedDateTime, buckets: Map[BucketId, Bucket]): Bucket = {
    buckets.find{ b => b._1 == bId && b._2.isActive(curDateTime)}.map(_._2).
      getOrElse(createNewBucket(bId, bucketRule, curDateTime))
  }

  def createUpdatedBuckets(resourceType: ResourceType, resourceName: ResourceName,
                           curDateTime: ZonedDateTime, buckets: Map[BucketId, Bucket],
                           usageRule: ResourceUsageRule, persistAllBucketUsages: Boolean):
  Map[Int, (Bucket, Option[ResourceBucketUsageUpdated])] = {
    usageRule.bucketRules.map { case (bId, bucketRule) =>
      val bucketToBeUpdated = getExistingOrNewBucket(bId, bucketRule, curDateTime, buckets)
      val updatedBucket = bucketToBeUpdated.copy(usedCount = bucketToBeUpdated.usedCount + 1)
      val updatedBucketState = if (persistAllBucketUsages || bucketRule.persistUsageState) {
        val startDate: Long = updatedBucket.startDateTime.map(getMillisFromZonedDateTime).getOrElse(-1)
        val endDate: Long = updatedBucket.endDateTime.map(getMillisFromZonedDateTime).getOrElse(-1)
        Option(ResourceBucketUsageUpdated(resourceType, resourceName, bId, updatedBucket.usedCount, startDate, endDate))
      } else None
      bId -> (updatedBucket, updatedBucketState)
    }
  }

  def updateResourceUsage(entityId: EntityId, resourceType: ResourceType, resourceName: ResourceName,
                          metricsWriter: MetricsWriter):
  Option[PersistUpdatedBucketState] = {
    metricsWriter.runWithSpan("updateResourceUsage", "BucketBasedResourceUsageTracker", InternalSpan) {
      val curDate = getCurrentUTCZonedDateTime
      ResourceUsageRuleHelper.getResourceUsageRule(entityId, resourceType, resourceName).map { usageRule =>
        val curResourceUsages = resourceUsages.get(resourceName).map(_.buckets).getOrElse(Map.empty)
        val updatedBucketsDetail = createUpdatedBuckets(resourceType, resourceName, curDate, curResourceUsages,
          usageRule, ResourceUsageRuleHelper.resourceUsageRules.persistAllBucketUsages)
        val updatedBuckets = updatedBucketsDetail.map(e => e._1 -> e._2._1)
        val updatedBucketsToBePersisted = updatedBucketsDetail.filter(e => e._2._2.isDefined).map(e => e._2._2.get).toSet
        replaceExistingBuckets(resourceType, resourceName, updatedBuckets)
        cleanupStaleData(resourceName, usageRule.bucketRules.keySet)
        PersistUpdatedBucketState(updatedBucketsToBePersisted)
      }
    }
  }

  def updateBucketUsage(resourceName: ResourceName, resourceBuckets: ResourceBuckets): Unit = {
    resourceUsages = resourceUsages + (resourceName -> resourceBuckets)
  }

  def replaceExistingBuckets(resourceType: ResourceType, resourceName: ResourceName, newBuckets: Map[BucketId, Bucket]): Unit = {
    updateBucketUsage(resourceName, tracking.ResourceBuckets(resourceType, newBuckets))
  }

  def addToExistingBuckets(resourceType: ResourceType, resourceName: ResourceName, newBuckets: Map[BucketId, Bucket]): Unit = {
    val curResourceUsages = resourceUsages.get(resourceName).map(_.buckets).getOrElse(Map.empty)
    updateBucketUsage(resourceName, tracking.ResourceBuckets(resourceType, curResourceUsages ++ newBuckets))
  }

  def getResourceUsageByBuckets(resourceName: ResourceName): ResourceUsagesByBuckets = {
    ResourceUsagesByBuckets(resourceUsages.get(resourceName).map(_.buckets).
      getOrElse(Map.empty).map(b => b._1 -> b._2.usedCount))
  }

  def getAllResourceBuckets: Map[ResourceName, ResourceBuckets] = resourceUsages

}

case class PersistUpdatedBucketState(entries: Set[ResourceBucketUsageUpdated])