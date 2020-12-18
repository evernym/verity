package com.evernym.verity.actor.resourceusagethrottling.tracking

import java.time.ZonedDateTime

import akka.actor.{Actor, ActorRef, Props}
import akka.event.LoggingReceive
import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.actor.node_singleton.ResourceBlockingStatusMngrCache
import com.evernym.verity.actor.persistence.{BasePersistentActor, Done, SnapshotConfig, SnapshotterExt}
import com.evernym.verity.actor.resourceusagethrottling._
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.http.route_handlers.restricted.{UpdateResourcesUsageCounter, UpdateResourcesUsageLimit}
import com.evernym.verity.actor.resourceusagethrottling.helper._
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.UpdateBlockingStatus
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.UpdateWarningStatus
import com.evernym.verity.config.CommonConfig.{USAGE_RULES, VIOLATION_ACTION}

import scala.concurrent.Future


class ResourceUsageTracker (val appConfig: AppConfig, actionExecutor: UsageViolationActionExecutor)
  extends BasePersistentActor
    with SnapshotterExt[ResourceUsageState]{

  override val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case aru: AddResourceUsage              => addResourceUsage(aru)
    case gru: GetResourceUsage              => sendResourceUsage(gru)
    case GetAllResourceUsages               => sender ! getResourceUsages
    case urul: UpdateResourcesUsageLimit    => updateResourceUsageLimit(urul)
    case uruc: UpdateResourcesUsageCounter  => updateResourceUsageCounter(uruc)
    case _ @ (_: UpdateBlockingStatus |
              _:UpdateWarningStatus)        => //nothing to do
  }

  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case rus: ResourceUsageState => resourceUsageTracker.updateWithSnapshotState(rus)
  }

  override def receiveEvent: Receive = {
    case beu: ResourceBucketUsageUpdated    =>
      val startDateTime =
        if (beu.startDateTime == -1) None else Option(getZonedDateTimeFromMillis(beu.startDateTime)(UTCZoneId))
      val endDateTime =
        if (beu.endDateTime == -1) None else Option(getZonedDateTimeFromMillis(beu.endDateTime)(UTCZoneId))
      resourceUsageTracker.addToExistingBuckets(beu.resourceType, beu.resourceName,
        Map(beu.bucketId -> Bucket(beu.count, persistUsages = true, startDateTime, endDateTime)))

    case rulu: ResourceUsageLimitUpdated    => resourceUsageTracker.updateResourceUsageLimit(rulu)
    case rucu: ResourceUsageCounterUpdated  => resourceUsageTracker.updateResourceUsageCounter(rucu)
  }

  override lazy val snapshotConfig: SnapshotConfig = SnapshotConfig(
    snapshotEveryNEvents = Option(ResourceUsageRuleHelper.resourceUsageRules.snapshotAfterEvents),
    keepNSnapshots = Option(1),
    deleteEventsOnSnapshot = true)

  override def snapshotState: Option[ResourceUsageState] = Option(resourceUsageTracker.getSnapshotState)

  val resourceUsageTracker = new BucketBasedResourceUsageTracker

  override lazy val persistenceEncryptionKey: String =
    appConfig.getConfigStringReq(CommonConfig.SECRET_RESOURCE_USAGE_TRACKER)

  def getResourceUsages: ResourceUsages = try {
    val allResourceUsages = resourceUsageTracker.getAllResources.map { case (resourceName, resourceBuckets) =>
      val rur = ResourceUsageRuleHelper.getResourceUsageRule(entityId, resourceBuckets.`type`, resourceName).getOrElse(
        throw new RuntimeException("resource usage rule not found"))
      resourceName -> resourceBuckets.buckets.map { case (bucketId, bucket) =>
        val allowedCount =
          resourceUsageTracker.getCustomLimit(resourceName, bucketId).
            getOrElse(rur.bucketRules.get(bucketId).map(_.allowedCount).
              getOrElse(-1))
        val bucketExt = BucketExt(bucket.usedCount, allowedCount, bucket.startDateTime, bucket.endDateTime)
        bucketId.toString -> bucketExt
      }
    }.filter(_._2.nonEmpty)
    ResourceUsages(allResourceUsages.filter(_._2.nonEmpty))
  } catch {
    case e: Exception =>
      logger.error("error occurred while building resource usages: " + Exceptions.getErrorMsg(e))
      throw e
  }

  def addResourceUsage(aru: AddResourceUsage): Unit = {
    runWithInternalSpan("addResourceUsage", "ResourceUsageTracker") {
      ResourceUsageRuleHelper.loadResourceUsageRules()
      if (ResourceUsageRuleHelper.resourceUsageRules.applyUsageRules) {
        val persistUpdatedBucketEntries =
          resourceUsageTracker.updateResourceUsage(aru.apiToken, aru.resourceType, aru.resourceName)
        persistUpdatedBucketEntries.foreach { ube =>
          ube.entries.foreach(asyncWriteWithoutApply)
        }
        analyzeUsage(aru)
      }
      if (aru.sendBackAck)
        sender ! Done
    }
  }

  def sendResourceUsage(gru: GetResourceUsage): Unit = {
    val ru = resourceUsageTracker.getResourceUsageByBuckets(gru.resourceName)
    sender ! ru
  }

  def updateResourceUsageLimit(urul: UpdateResourcesUsageLimit): Unit = {
    urul.resourceUsageLimits.foreach { srul =>
      val currentBucketInfo = resourceUsageTracker.resourceUsages.get(srul.resourceName).flatMap(_.buckets.get(srul.bucketId))
      val newLimit = (srul.newLimit, srul.addToCurrentUsedCount) match {
        case (Some(nl), None)       => nl
        case (None,     Some(atcc)) => currentBucketInfo.map(_.usedCount).getOrElse(0) + atcc
        case _                      =>
          throw new BadRequestErrorException(BAD_REQUEST.statusCode,
            Option("one and only one of these should be specified: 'newLimit' or 'addToCurrentUsedCount'"))

      }
      writeAndApply(ResourceUsageLimitUpdated(srul.resourceName, srul.bucketId, newLimit))
    }
    sender ! Done
  }

  def updateResourceUsageCounter(urul: UpdateResourcesUsageCounter): Unit = {
    urul.resourceUsageCounters.foreach { rc =>
      writeAndApply(ResourceUsageCounterUpdated(rc.resourceName, rc.bucketId, rc.newCount.getOrElse(0)))
    }
    sender ! Done
  }

  /**
   * checks if resource usage has exceeded configured limits and take appropriate actions if that is the case
   * @param aru
   */
  def analyzeUsage(aru: AddResourceUsage): Unit = {
    runWithInternalSpan("analyzeUsage", "ResourceUsageTracker") {
      Future {
        ResourceUsageRuleHelper.getResourceUsageRule(aru.apiToken, aru.resourceType, aru.resourceName).foreach { usageRule =>
          val actualUsages = resourceUsageTracker.getResourceUsageByBuckets(aru.resourceName)
          usageRule.bucketRules.foreach { case (bucketId, bucketRule) =>
            actualUsages.usages.get(bucketId).foreach { actualCount =>
              val customAllowedCount: Option[Int] = resourceUsageTracker.getCustomLimit(aru.resourceName, bucketId)
              val allowedCount = customAllowedCount.getOrElse(bucketRule.allowedCount)
              if (actualCount >= allowedCount) {
                val rulePath =
                  s"""$USAGE_RULES.${
                    if (customAllowedCount.isDefined) "custom" else "default"
                  }.${
                    ResourceUsageRuleHelper.getHumanReadableResourceType(aru.resourceType)
                  }.${aru.resourceName}"""
                val actionPath = VIOLATION_ACTION
                val vr = ViolatedRule(entityId, aru.resourceName, bucketRule, actualCount, rulePath, bucketId, actionPath)
                actionExecutor.execute(bucketRule.violationActionId, vr)(self)
              }
            }
          }
        }
      }
    }
  }

}

object ResourceUsageTracker {

  def props(appConfig: AppConfig, actionExecutor: UsageViolationActionExecutor): Props =
    Props(new ResourceUsageTracker(appConfig, actionExecutor))

  /**
   *
   * @param entityId an entity id being tracked
   * @param resourceName a resource name being tracked
   * @param apiToken a token used to find out a specific applicable rule
   */
  private def checkIfUsageIsBlocked(entityId: EntityId, resourceName: ResourceName, apiToken: ApiToken): Unit = {
    val isBlacklisted = ResourceUsageRuleHelper.resourceUsageRules.isBlacklisted(apiToken, entityId)
    if (isBlacklisted) {
      throw new BadRequestErrorException(USAGE_BLOCKED.statusCode)
    }
    val isWhitelisted = ResourceUsageRuleHelper.resourceUsageRules.isWhitelisted(apiToken, entityId)
    if (! isWhitelisted) {
      ResourceBlockingStatusMngrCache.checkIfUsageBlocked(entityId, resourceName)
    }
  }

  /**
   *
   * @param ipAddress ipAddress
   * @param resourceType endpoint or message
   * @param resourceName resource name being tracked
   * @param sendBackAck
   * @param userIdOpt
   * @param rut
   */
  def addUserResourceUsage(ipAddress: IpAddress, resourceType: ResourceType, resourceName: ResourceName,
                           sendBackAck: Boolean, userIdOpt: Option[UserId])(rut: ActorRef): Unit = {
    // Do NOT increment global counter if entityId or userIdOpt is blocked.
    // global MUST be AFTER entityId and userIdOpt.
    val trackByEntityIds = Option(ipAddress) ++ userIdOpt ++ Option("global")
    val resourceNames = Set(RESOURCE_NAME_ALL, resourceName)

    trackByEntityIds.foreach { entityId =>
      resourceNames.foreach { rn =>
        checkIfUsageIsBlocked(entityId, rn, ipAddress)
      }
      resourceNames.foreach { rn =>
        val isWhitelisted = ResourceUsageRuleHelper.resourceUsageRules.isWhitelisted(ipAddress, entityId)
        if (! isWhitelisted) {
          if (! ResourceBlockingStatusMngrCache.isInUnblockingPeriod(entityId, rn)) {
            val aru = tracking.AddResourceUsage(resourceType, rn, ipAddress, sendBackAck)
            rut.tell(ForIdentifier(entityId, aru), Actor.noSender)
          }
        }
      }
    }
  }

  def sendToResourceUsageTracker(entityId: EntityId, cmd: Any)(rut: ActorRef): Unit = {
    rut.tell(ForIdentifier(entityId, cmd), Actor.noSender)
  }
}

case class Bucket(usedCount: Int = 0,
                  persistUsages: Boolean=false,
                  startDateTime: Option[ZonedDateTime]=None,
                  endDateTime: Option[ZonedDateTime]=None) {

  override def toString: String =  s"${usedCount.toString} ($startDateTime - $endDateTime)"

  def isActive(date: ZonedDateTime): Boolean = {
    (startDateTime.forall(_.equals(date)) || startDateTime.forall(_.isBefore(date))) &&
      (endDateTime.forall(_.equals(date)) || endDateTime.forall(_.isAfter(date)))
  }
}

case class BucketExt(usedCount: Int = 0, allowedCount: Int,
                     startDateTime: Option[ZonedDateTime], endDateTime: Option[ZonedDateTime])

case class AddResourceUsage(resourceType: ResourceType, resourceName: ResourceName, apiToken: ApiToken, sendBackAck: Boolean=true) extends ActorMessageClass
case class GetResourceUsage(resourceName: ResourceName) extends ActorMessageClass
case class ResourceUsages(usages: Map[ResourceName, Map[BucketIdStr, BucketExt]]) extends ActorMessageClass

case object GetAllResourceUsages  extends ActorMessageObject
