package com.evernym.verity.actor.resourceusagethrottling

import akka.actor.{ActorRef, ReceiveTimeout}
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.BlockingDetail
import com.evernym.verity.actor.resourceusagethrottling.tracking.{GetAllResourceUsages, ResourceUsageTracker, ResourceUsages}
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import java.time.{DateTimeException, ZonedDateTime}
import java.time.temporal.ChronoUnit

import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageRuleConfig

trait BaseResourceUsageTrackerSpec
  extends PersistentActorSpec
    with BasicSpec
    with Eventually {

  override def beforeAll(): Unit = {
    { val r = platform.resourceUsageTrackerRegion }
  }

  protected lazy val resourceUsageTracker: ActorRef = platform.resourceUsageTrackerRegion

  def sendToResourceUsageTrackerRegion(to: String, cmd: Any, restartActorBefore: Boolean=false): Unit = {
    if (restartActorBefore) {
      restartResourceUsageTrackerActor(to)
    }

    resourceUsageTracker ! ForIdentifier(to, cmd)
  }

  def restartResourceUsageTrackerActor(to: String): Unit = {
    resourceUsageTracker ! ForIdentifier(to, ReceiveTimeout)
    Thread.sleep(2000)
  }

  def blockDurationInSeconds(status: BlockingDetail): Long = {
    val from: ZonedDateTime = status.blockFrom.getOrElse(throw new DateTimeException("Must provide a blockFrom ZonedDatetime"))
    val to: ZonedDateTime = status.blockTill.getOrElse(throw new DateTimeException("Must provide a blockTill ZonedDatetime"))
    ChronoUnit.SECONDS.between(from, to)
  }

  def sendToResourceUsageTracker(resourceType: ResourceType,
                                 resourceName: ResourceName,
                                 ipAddress: IpAddress,
                                 userIdOpt: Option[UserId],
                                 resourceUsageRules: ResourceUsageRuleConfig,
                                 restartActorBefore: Boolean=false): Unit = {
    if (restartActorBefore) {
      (Option(ipAddress) ++ userIdOpt).foreach { entityId =>
        restartResourceUsageTrackerActor(entityId)
      }
    }

    ResourceUsageTracker.addUserResourceUsage(resourceType, resourceName,
      ipAddress, userIdOpt, sendBackAck = false, resourceUsageRules)(resourceUsageTracker)
  }

  def checkUsage(entityId: EntityId,
                 expectedUsages: ResourceUsages): Unit = {
    sendToResourceUsageTrackerRegion(entityId, GetAllResourceUsages)
    val actualUsages = expectMsgType[ResourceUsages]
    expectedUsages.usages.foreach { expResourceUsage =>
      val actualResourceUsageOpt = actualUsages.usages.get(expResourceUsage._1)
      actualResourceUsageOpt.isDefined shouldBe true
      val actualResourceUsage = actualResourceUsageOpt.get
      expResourceUsage._2.foreach { case (expBucketId, expBucketExt) =>
        val actualBucketExtOpt = actualResourceUsage.get(expBucketId)
        actualBucketExtOpt.isDefined shouldBe true
        val actualBucketExt = actualBucketExtOpt.get
        actualBucketExt.usedCount shouldBe expBucketExt.usedCount
        actualBucketExt.allowedCount shouldBe expBucketExt.allowedCount
        actualBucketExt.startDateTime.isDefined shouldBe true
        actualBucketExt.endDateTime.isDefined shouldBe true
      }
    }
  }

}
