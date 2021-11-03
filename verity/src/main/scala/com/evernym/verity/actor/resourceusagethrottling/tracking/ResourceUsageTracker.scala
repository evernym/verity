package com.evernym.verity.actor.resourceusagethrottling.tracking

import java.time.ZonedDateTime
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.event.LoggingReceive
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.node_singleton.{ResourceBlockingStatusMngrCache, ResourceWarningStatusMngrCache}
import com.evernym.verity.actor.persistence.{BasePersistentActor, SnapshotConfig, SnapshotterExt}
import com.evernym.verity.actor.resourceusagethrottling._
import com.evernym.verity.config.{AppConfig, ConfigConstants}
import com.evernym.verity.http.route_handlers.restricted.{UpdateResourcesUsageCounter, UpdateResourcesUsageLimit}
import com.evernym.verity.actor.resourceusagethrottling.helper._
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.UpdateBlockingStatus
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.UpdateWarningStatus
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil.{getResourceSimpleName, getResourceTypeName}
import com.evernym.verity.config.ConfigConstants.USAGE_RULES
import com.evernym.verity.observability.metrics.InternalSpan
import com.evernym.verity.util2.Exceptions

import scala.concurrent.{ExecutionContext, Future}


class ResourceUsageTracker (val appConfig: AppConfig,
                            actionExecutor: UsageViolationActionExecutor,
                            executionContext: ExecutionContext)
  extends BasePersistentActor
    with SnapshotterExt[ResourceUsageState]{

  def resourceUsageRuleHelper: ResourceUsageRuleHelper = ResourceUsageRuleHelperExtension(context.system).get()
  override def futureExecutionContext: ExecutionContext = executionContext
  private implicit val executionContextImplc: ExecutionContext = executionContext

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
    snapshotEveryNEvents = Option(resourceUsageRuleHelper.resourceUsageRules.snapshotAfterEvents),
    keepNSnapshots = Option(1),
    deleteEventsOnSnapshot = true)

  override def snapshotState: Option[ResourceUsageState] = Option(resourceUsageTracker.getSnapshotState)

  val resourceUsageTracker = new BucketBasedResourceUsageTracker(resourceUsageRuleHelper)

  override lazy val persistenceEncryptionKey: String =
    appConfig.getStringReq(ConfigConstants.SECRET_RESOURCE_USAGE_TRACKER)

  def getResourceUsages: ResourceUsages = try {
    val allResourceUsages = resourceUsageTracker.getAllResourceBuckets.map { case (resourceName, resourceBuckets) =>
      val rur = resourceUsageRuleHelper.getResourceUsageRule(entityId, resourceBuckets.`type`, resourceName).getOrElse(
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
    metricsWriter.runWithSpan("addResourceUsage", "ResourceUsageTracker", InternalSpan) {
      resourceUsageRuleHelper.loadResourceUsageRules(appConfig.config)
      if (resourceUsageRuleHelper.resourceUsageRules.applyUsageRules) {
        val persistUpdatedBucketEntries =
          resourceUsageTracker.updateResourceUsage(entityId, aru.resourceType, aru.resourceName, metricsWriter)
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
    metricsWriter.runWithSpan("analyzeUsage", "ResourceUsageTracker", InternalSpan) {
      Future {
        resourceUsageRuleHelper.getResourceUsageRule(entityId, aru.resourceType, aru.resourceName).foreach { usageRule =>
          val actualUsages = resourceUsageTracker.getResourceUsageByBuckets(aru.resourceName)
          usageRule.bucketRules.foreach { case (bucketId, bucketRule) =>
            actualUsages.usages.get(bucketId).foreach { actualCount =>
              val customAllowedCount: Option[Int] = resourceUsageTracker.getCustomLimit(aru.resourceName, bucketId)
              val allowedCount = customAllowedCount.getOrElse(bucketRule.allowedCount)
              if (actualCount >= allowedCount) {
                val rulePath = ResourceUsageTracker.violationRulePath(
                  resourceUsageRuleHelper.getRuleNameByEntityId(entityId),
                  aru.resourceType,
                  aru.resourceName,
                  customAllowedCount
                )
                val vr = ViolatedRule(entityId, aru.resourceName, rulePath, bucketId, bucketRule, actualCount)
                try {
                  actionExecutor.execute(bucketRule.violationActionId, vr)(self)
                } catch {
                  case e: Exception =>
                    logger.error(s"[$entityId] error while executing resource usage violation action: $vr, error: ${e.getMessage}")
                }
              }
            }
          }
        }
      }
    }
  }

}

object ResourceUsageTracker {

  def props(appConfig: AppConfig, actionExecutor: UsageViolationActionExecutor, executionContext: ExecutionContext): Props =
    Props(new ResourceUsageTracker(appConfig, actionExecutor, executionContext))
  val defaultPassivationTimeout = 600

//  /**
//   *
//   * @param ipAddress ip address
//   * @param entityId an entity id being tracked
//   * @param resourceName a resource name being tracked
//   */
//  def checkIfUsageIsBlocked(ipAddress: IpAddress,
//                            entityId: EntityId,
//                            resourceName: ResourceName)(implicit as:ActorSystem): Unit = {
//    checkIfUsageIsBlocked(ipAddress, entityId, resourceName, ResourceUsageRuleHelper.resourceUsageRules)
//  }

  /**
   *
   * @param ipAddress ip address
   * @param entityId an entity id being tracked
   * @param resourceName a resource name being tracked
   */
  def checkIfUsageIsBlocked(ipAddress: IpAddress,
                            entityId: EntityId,
                            resourceName: ResourceName,
                            resourceUsageRuleConfig:  ResourceUsageRuleConfig)(implicit as: ActorSystem): Unit = {
    val isBlacklisted = resourceUsageRuleConfig.isBlacklisted(ipAddress, entityId)
    if (isBlacklisted) {
      throw new BadRequestErrorException(USAGE_BLOCKED.statusCode, Option("usage blocked"))
    }
    val isWhitelisted = resourceUsageRuleConfig.isWhitelisted(ipAddress, entityId)
    if (! isWhitelisted) {
      ResourceBlockingStatusMngrCache(as).checkIfUsageBlocked(entityId, resourceName)
    }
  }

  /**
   * @param resourceType endpoint or message
   * @param resourceName resource name being tracked
   * @param sendBackAck shall an ack being sent back
   * @param ipAddress ipAddress from which request arrived
   * @param userIdOpt user id being tracked
   * @param rut
   */
  def addUserResourceUsage(resourceType: ResourceType,
                           resourceName: ResourceName,
                           ipAddress: IpAddress,
                           userIdOpt: Option[UserId],
                           sendBackAck: Boolean,
                           resourceUsageRules: ResourceUsageRuleConfig)(rut: ActorRef)(implicit as: ActorSystem): Unit = {
    // Do NOT increment global counter if ipAddressOpt or userIdOpt is blocked.
    // global MUST be AFTER `ipAddressOpt` and `userIdOpt`.
    val trackByEntityIds = Option(ipAddress) ++ userIdOpt ++ Option(ENTITY_ID_GLOBAL)

    val resourceNameAll = resourceType match {
      case RESOURCE_TYPE_ENDPOINT => RESOURCE_NAME_ENDPOINT_ALL
      case RESOURCE_TYPE_MESSAGE => RESOURCE_NAME_MESSAGE_ALL
    }
    val resourceNames = Set(resourceName, resourceNameAll)

    trackByEntityIds.foreach { entityId =>
      //check if tracked entity is NOT blocked
      resourceNames.foreach { rn =>
        checkIfUsageIsBlocked(ipAddress, entityId, rn, resourceUsageRules)
      }

      //track if neither 'whitelisted' nor in 'UnblockingPeriod'
      resourceNames.foreach { resourceName =>
        val isWhitelisted = resourceUsageRules.isWhitelisted(ipAddress, entityId)
        if (! isWhitelisted) {
          if (! ResourceWarningStatusMngrCache(as).isUnwarned(entityId, resourceName) &&
            ! ResourceBlockingStatusMngrCache(as).isUnblocked(entityId, resourceName))
          {
            val aru = tracking.AddResourceUsage(resourceType, resourceName, sendBackAck)
            rut.tell(ForIdentifier(entityId, aru), Actor.noSender)
          }
        }
      }
    }
  }

  def sendToResourceUsageTracker(entityId: EntityId, cmd: Any)(rut: ActorRef): Unit = {
    rut.tell(ForIdentifier(entityId, cmd), Actor.noSender)
  }

  def violationRulePath(ruleName: UsageRuleName,
                        resourceType: ResourceType,
                        resourceName: ResourceName,
                        customAllowedCount: Option[Int]): String = {
    s"""$USAGE_RULES.${
      if (customAllowedCount.isDefined) "custom-limit" else ruleName
    }.${
      getResourceTypeName(resourceType)
    }.${
      getResourceSimpleName(resourceName)
    }"""
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

case class BucketExt(usedCount: Int = 0,
                     allowedCount: Int,
                     startDateTime: Option[ZonedDateTime],
                     endDateTime: Option[ZonedDateTime])

case class AddResourceUsage(resourceType: ResourceType,
                            resourceName: ResourceName,
                            sendBackAck: Boolean=true) extends ActorMessage
case class GetResourceUsage(resourceName: ResourceName) extends ActorMessage
case class ResourceUsages(usages: Map[ResourceName, Map[BucketIdStr, BucketExt]]) extends ActorMessage

case object GetAllResourceUsages extends ActorMessage
