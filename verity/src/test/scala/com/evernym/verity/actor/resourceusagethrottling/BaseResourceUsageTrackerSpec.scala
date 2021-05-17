package com.evernym.verity.actor.resourceusagethrottling

import akka.actor.{ActorRef, ReceiveTimeout}
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.BlockingDetail
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually

import java.time.{DateTimeException, ZonedDateTime}
import java.time.temporal.ChronoUnit

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
                                 restartActorBefore: Boolean=false): Unit = {
    if (restartActorBefore) {
      (Option(ipAddress) ++ userIdOpt).foreach { entityId =>
        restartResourceUsageTrackerActor(entityId)
      }
    }

    ResourceUsageTracker.addUserResourceUsage(resourceType, resourceName,
      ipAddress, userIdOpt, sendBackAck = false)(resourceUsageTracker)
  }
}
