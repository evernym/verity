package com.evernym.verity.actor

import java.time.temporal.ChronoUnit
import java.time.{DateTimeException, ZonedDateTime}

import akka.actor.{ActorRef, ReceiveTimeout}
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.actor.cluster_singleton._
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking._
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning._
import com.evernym.verity.actor.node_singleton.{ResourceBlockingStatusMngrCache, ResourceWarningStatusMngrCache}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.resourceusagethrottling.tracking._
import com.evernym.verity.actor.resourceusagethrottling.{tracking, _}
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.route_handlers.restricted.{ResourceUsageCounterDetail, UpdateResourcesUsageCounter}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TimeZoneUtil.getCurrentUTCZonedDateTime
import com.typesafe.scalalogging.Logger
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.duration._

class ResourceUsageTrackerSpec extends PersistentActorSpec with BasicSpec with Eventually {

  val logger: Logger = getLoggerByClass(classOf[ResourceUsageTrackerSpec])

  val DUMMY_MSG = "DUMMY_MSG"
  val globalToken = "global"
  val user1IpAddress = "127.1.0.1"
  val user2IpAddress = "127.2.0.2"
  val user3IpAddress = "127.3.0.3"
  val user4IpAddress = "127.4.0.4"
  val user1DID = "111111111111111111111"
  val user2DID = "222222222222222222222"
  val user3DID = "333333333333333333333"
  val user4DID = "444444444444444444444"
  val createMsgConnReq: String = MSG_TYPE_CREATE_MSG + "_" + CREATE_MSG_TYPE_CONN_REQ

  lazy val resourceUsageTrackerRegion: ActorRef = platform.resourceUsageTrackerRegion

  def emptyBlockingDetail: BlockingDetail = BlockingDetail(None, None, None, None)
  def emptyWarningDetail: WarningDetail = WarningDetail(None, None, None, None)

  var bd: BlockingDetail = _
  var wd: WarningDetail = _

  def resourceUsageTrackerSpec() {

    "BlockingDetail" - {
      "when only blockFrom is set" - {
        "and tested isBlocked method for few future times" - {
          "should respond with true" in {
            val curDateTime = getCurrentUTCZonedDateTime
            bd = emptyBlockingDetail.copy(blockFrom = Some(curDateTime))
            bd.isBlocked(curDateTime.plusSeconds(1)) shouldBe true
            bd.isBlocked(curDateTime.plusSeconds(11)) shouldBe true
          }
        }
      }

      "when blockFrom and blockTill both are set" - {
        "and tested isBlocked method for few future times" - {
          "should respond accordingly" in {
            val curDateTime = getCurrentUTCZonedDateTime
            bd = emptyBlockingDetail.copy(blockFrom = Some(curDateTime),
              blockTill = Some(curDateTime.plusSeconds(10)))
            bd.isBlocked(curDateTime.plusSeconds(1)) shouldBe true
            bd.isBlocked(curDateTime.plusSeconds(11)) shouldBe false
          }
        }
      }

      "when only unblockFrom is set" - {
        "and tested few helper methods for few future times" - {
          "should respond accordingly" in {
            val curDateTime = getCurrentUTCZonedDateTime
            bd = emptyBlockingDetail.copy(unblockFrom = Some(curDateTime))
            bd.isInBlockingPeriod(curDateTime.plusSeconds(1)) shouldBe false
            bd.isInUnblockingPeriod(curDateTime.plusSeconds(1)) shouldBe true
            bd.isBlocked(curDateTime.plusSeconds(1)) shouldBe false
          }
        }
      }

      "when unblockFrom and unblockTill both are set" - {
        "and tested few helper methods for few future times" - {
          "should respond accordingly" in {
            val curDateTime = getCurrentUTCZonedDateTime
            bd = emptyBlockingDetail.copy(unblockFrom = Some(curDateTime),
              unblockTill = Option(curDateTime.plusSeconds(10)))
            bd.isInBlockingPeriod(curDateTime.plusSeconds(1)) shouldBe false
            bd.isInUnblockingPeriod(curDateTime.plusSeconds(1)) shouldBe true
            bd.isBlocked(curDateTime.plusSeconds(1)) shouldBe false
            bd.isInBlockingPeriod(curDateTime.plusSeconds(11)) shouldBe false
            bd.isInUnblockingPeriod(curDateTime.plusSeconds(11)) shouldBe false
            bd.isBlocked(curDateTime.plusSeconds(11)) shouldBe false
          }
        }
      }

      "when blockFrom and unblockFrom both are set" - {
        "and tested few helper methods for few future times" - {
          "should respond accordingly" in {
            val curDateTime = getCurrentUTCZonedDateTime
            bd = emptyBlockingDetail.copy(blockFrom = Some(curDateTime), unblockFrom = Some(curDateTime))
            bd.isInBlockingPeriod(curDateTime.plusSeconds(1)) shouldBe true
            bd.isInUnblockingPeriod(curDateTime.plusSeconds(1)) shouldBe true
            bd.isBlocked(curDateTime.plusSeconds(1)) shouldBe false //unblocking period takes precedence
          }
        }
      }

      "when blockFrom, unblockFrom and unblockTill are set" - {
        "and tested few helper methods for few future times" - {
          "should respond accordingly" in {
            val curDateTime = getCurrentUTCZonedDateTime
            bd = emptyBlockingDetail.copy(blockFrom = Some(curDateTime), unblockFrom = Some(curDateTime),
              unblockTill = Some(curDateTime.plusSeconds(10)))
            bd.isInBlockingPeriod(curDateTime.plusSeconds(1)) shouldBe true
            bd.isInBlockingPeriod(curDateTime.plusSeconds(11)) shouldBe true
            bd.isInUnblockingPeriod(curDateTime.plusSeconds(11)) shouldBe false
            bd.isBlocked(curDateTime.plusSeconds(11)) shouldBe true
          }
        }
      }
    }

    "WarningDetail" - {

      "when only warnFrom is set" - {
        "and tested isWarned method for few future times" - {
          "should respond with true" in {
            val curDateTime = getCurrentUTCZonedDateTime
            wd = emptyWarningDetail.copy(warnFrom = Some(curDateTime))
            wd.isWarned(curDateTime.plusSeconds(1)) shouldBe true
            wd.isWarned(curDateTime.plusSeconds(11)) shouldBe true
          }
        }
      }

      "when warnFrom and warnTill both are set" - {
        "and tested isWarned method for few future times" - {
          "should respond accordingly" in {
            val curDateTime = getCurrentUTCZonedDateTime
            wd = emptyWarningDetail.copy(warnFrom = Some(curDateTime), warnTill = Some(curDateTime.plusSeconds(10)))
            wd.isWarned(curDateTime.plusSeconds(1)) shouldBe true
            wd.isWarned(curDateTime.plusSeconds(11)) shouldBe false
          }
        }
      }
      "when only unwarnFrom is set" - {
        "and tested few helper methods for few future times" - {
          "should respond accordingly" in {
            val curDateTime = getCurrentUTCZonedDateTime
            wd = emptyWarningDetail.copy(unwarnFrom = Some(curDateTime))
            wd.isInWarningPeriod(curDateTime.plusSeconds(1)) shouldBe false
            wd.isInUnwarningPeriod(curDateTime.plusSeconds(1)) shouldBe true
            wd.isWarned(curDateTime.plusSeconds(1)) shouldBe false
          }
        }
      }

      "when unwarnFrom and unwarnTill both are set" - {
        "and tested few helper methods for few future times" - {
          "should respond accordingly" in {
            val curDateTime = getCurrentUTCZonedDateTime
            wd = emptyWarningDetail.copy(unwarnFrom = Some(curDateTime), unwarnTill = Option(curDateTime.plusSeconds(10)))
            wd.isInWarningPeriod(curDateTime.plusSeconds(1)) shouldBe false
            wd.isInUnwarningPeriod(curDateTime.plusSeconds(1)) shouldBe true
            wd.isWarned(curDateTime.plusSeconds(1)) shouldBe false

            wd.isInWarningPeriod(curDateTime.plusSeconds(11)) shouldBe false
            wd.isInUnwarningPeriod(curDateTime.plusSeconds(11)) shouldBe false
            wd.isWarned(curDateTime.plusSeconds(11)) shouldBe false
          }
        }
      }

      "when warnFrom and unwarnFrom both are set" - {
        "and tested few helper methods for few future times" - {
          "should respond accordingly" in {
            val curDateTime = getCurrentUTCZonedDateTime
            wd = emptyWarningDetail.copy(warnFrom = Some(curDateTime), unwarnFrom = Some(curDateTime))
            wd.isInWarningPeriod(curDateTime.plusSeconds(1)) shouldBe true
            wd.isInUnwarningPeriod(curDateTime.plusSeconds(1)) shouldBe true
            wd.isWarned(curDateTime.plusSeconds(1)) shouldBe false //unwarning period takes precedence
          }
        }
      }

      "when warnFrom, unwarnFrom and unwarnTill are set" - {
        "and tested few helper methods for few future times" - {
          "should respond accordingly" in {
            val curDateTime = getCurrentUTCZonedDateTime
            wd = emptyWarningDetail.copy(warnFrom = Some(curDateTime), unwarnFrom = Some(curDateTime),
              unwarnTill = Some(curDateTime.plusSeconds(10)))
            wd.isInWarningPeriod(curDateTime.plusSeconds(1)) shouldBe true
            wd.isInWarningPeriod(curDateTime.plusSeconds(11)) shouldBe true
            wd.isInUnwarningPeriod(curDateTime.plusSeconds(11)) shouldBe false
            wd.isWarned(curDateTime.plusSeconds(11)) shouldBe true
          }
        }
      }
    }

    def restartResourceUsageTrackerActor(to: String): Unit = {
      resourceUsageTrackerRegion ! ForIdentifier(to, ReceiveTimeout)
      Thread.sleep(2000)
    }

    def sendToResourceUsageTracker(to: String,
                                   resourceType: ResourceType,
                                   resourceName: ResourceName,
                                   userIdOpt: Option[UserId],
                                   restartActorBefore: Boolean=false): Unit = {
      if (restartActorBefore) {
        restartResourceUsageTrackerActor(to)
      }

      ResourceUsageTracker.addUserResourceUsage(resourceType, resourceName,
        Option(to), userIdOpt, sendBackAck = false)(resourceUsageTrackerRegion)
    }

    def sendToResourceUsageTrackerRegion(to: String, cmd: Any, restartActorBefore: Boolean=false): Unit = {
      if (restartActorBefore) {
        restartResourceUsageTrackerActor(to)
      }

      resourceUsageTrackerRegion ! ForIdentifier(to, cmd)
    }

    // Begin resources and helper functions used in the testResourceGetsBlockedWarnedIfExceedsSetLimit test
    val resourceUsages: List[(String, Option[String], String, Option[Int])] = List(
      (user1IpAddress, None, createMsgConnReq, Some(3)),
      (user2IpAddress, Some(user2DID), DUMMY_MSG, Some(2)),
      (user4IpAddress, Some(user4DID), DUMMY_MSG, Some(2)),
      (globalToken, None, DUMMY_MSG, None)
    )

    // Reset counters for all resources involved in this test
    // Triples of entity Id, resource name, expected used count after looping 6 times above.
    val resourcesToUnblock: List[(String, String, Int, Int)] = List(
      (user1IpAddress, createMsgConnReq, 2, 600),
      (user2IpAddress, DUMMY_MSG, 3, 60),
      (user2DID, DUMMY_MSG, 3, 120),
      (user4IpAddress, DUMMY_MSG, 3, 60),
      (user4DID, DUMMY_MSG, 3, 120),
      (globalToken, DUMMY_MSG, 4, 180)
    )

    def resetResourceUsage(): Unit = {
      resourceUsages.foreach { resourceUsage =>
        // Clear all buckets in preparation for this test
        // Previous tests may have already added stats to each of the following buckets (300, 600, ..., etc).
        // CREATE_MSG_connReq
        sendToResourceUsageTrackerRegion(resourceUsage._1,
          UpdateResourcesUsageCounter(List(
            ResourceUsageCounterDetail(resourceUsage._3, 300, None),
            ResourceUsageCounterDetail(resourceUsage._3, 600, None),
            ResourceUsageCounterDetail(resourceUsage._3, 1200, None),
            ResourceUsageCounterDetail(resourceUsage._3, 1800, None),
            ResourceUsageCounterDetail(resourceUsage._3, BUCKET_ID_INDEFINITE_TIME, None)
          )))
        expectMsg(Done)
      }
    }

    def blockDurationInSeconds(status: BlockingDetail): Long = {
      val from: ZonedDateTime = status.blockFrom.getOrElse(throw new DateTimeException("Must provide a blockFrom ZonedDatetime"))
      val to: ZonedDateTime = status.blockTill.getOrElse(throw new DateTimeException("Must provide a blockTill ZonedDatetime"))
      ChronoUnit.SECONDS.between(from, to)
    }
    // End resources and helper functions used in the testResourceGetsBlockedWarnedIfExceedsSetLimit test

    def testResourceGetsBlockedWarnedIfExceedsSetLimit(): Unit = {
      resetResourceUsage()
      val maxMessages = 6
      (1 to maxMessages).foreach { i =>
        resourceUsages.foreach { resourceUsage =>
          // Skip "global" entityId when calling sendToResourceUsageTrackerAddResourceUsage.
          if(resourceUsage._1 != globalToken) {
            try {
              logger.debug(s"Adding resource usage for resourceName: ${resourceUsage._3} and IP/entityId: ${resourceUsage._1} and user DID: ${resourceUsage._2}")
              sendToResourceUsageTracker(resourceUsage._1, RESOURCE_TYPE_MESSAGE, resourceUsage._3, resourceUsage._2)
              expectNoMessage()
            } catch {
              case e: BadRequestErrorException =>
                logger.debug(s"Usage blocked resource: ${resourceUsage._2} for IP/entityId: ${resourceUsage._1} and user DID: ${resourceUsage._2}")
                assert(i >= resourceUsage._4.getOrElse(-1) && e.respCode.equals("GNR-123") && e.getMessage.equals("usage blocked"))
              case _: Exception =>
                fail("Unhandled exception encountered while adding resource usage stats: entityId: " +
                  resourceUsage._1 + " resourceType: " + RESOURCE_TYPE_MESSAGE + " resourceName: " + resourceUsage._3)
            }
            // Yield to other threads between calls to sendToResourceUsageTrackerRegionAddResourceUsage.
            // Don't overwhelm the resource usage tracker during this test so that numbers are predictable. It is
            // expected for limits to possibly exceeded thresholds, because of async processing and analyzing of
            // usage counts.
            Thread.sleep(10)
          }
        }
      }

      // Test blocked
      //
      // The following rule in resource-usage-rule-spec.conf (resource-usage-rule.conf file used for tests) causes
      // violation-action group 70 to call log-msg, warn-user, and block-resource after the second iteration; when
      // allowed-counts of 2 is exceeded on bucket -1 (infinite bucket).
      //
      // CREATE_MSG_connReq {
      //   300: {"allowed-counts": 5, "violation-action-id": 50}
      //   600: {"allowed-counts": 20, "violation-action-id": 70}
      //   1800: {"allowed-counts": 50, "violation-action-id": 90}
      //   -1: {"allowed-counts": 2, "violation-action-id": 70, "persist-usage-state": true}
      // }
      //
      // ... where violation-action group 70 is defined as:
      //
      // # log it and block only resource
      // 70 {
      //   log-msg: {"level": "info"}
      //   warn-user: {"track-by": "ip", "period": -1}
      //   block-resource: {"track-by": "ip", "period": 600}
      // }
      //
      // Expect the CREATE_MSG_connReq resource to be blocked for user1IpAddress
      //
      // The following rules in resource-usage-rule-spec.conf causes violation-action groups 100 - 103 to fire after
      // the third iteration (100-102 on the 4th iteration and 103 on the 5th iteration)
      //
      // DUMMY_MSG {
      //  300: {"allowed-counts": 3, "violation-action-id": 100}
      //  600: {"allowed-counts": 3, "violation-action-id": 101}
      //  1200: {"allowed-counts": 3, "violation-action-id": 102}
      //  1800: {"allowed-counts": 4, "violation-action-id": 103}
      // }
      //
      // ... where violation-action groups 100-103 are defined as:
      //
      // 100 {
      //  # Log regardless of entityId ("global", IP, or user DID)
      //  log-msg: {"level": "warn"}
      // }
      //
      // 101 {
      //   # Log only at debug level if and only if entityId is an IP address
      //   log-msg: {"track-by": "ip", "level": "info"}
      //   # Block if and only if entityId is an IP address
      //   block-resource: {"track-by": "ip", "period": 60}
      // }
      //
      // 102 {
      //   # Log only at trace level if and only if entityId is a DID (21 to 23 length)
      //   log-msg: {"track-by": "user", "level": "debug"}
      //   block-resource: {"track-by": "user", "period": 120}
      // }
      //
      // 103 {
      //   # Log only at info level if and only if entityId is "global"
      //   log-msg: {"track-by": "global", "level": "trace"}
      //   # Block if and only if entityId is "global"
      //   block-resource: {"track-by": "global", "period": 180}
      // }
      //
      // Expect the DUMMY_MSG resource to be blocked for user2IpAddress (action 101), user2DID (action 102),
      // and "global" (action 103)
      eventually (timeout(Span(10, Seconds))) {
        // Give time for the system to process all of the AddResourceUsage messages. The sleep causes GetBlockedList
        // to be called once per second for up to 10 seconds.
        Thread.sleep(1000)
        singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked = false,
          onlyActive = true, inChunks = false))
        expectMsgPF() {
          // Note: status.blockFrom and status.blockTill should always be equal.
          // Details:
          //       status.blockFrom and status.blockTill are "Empty" the first time
          //       testResourceGetsBlockedWarnedIfExceedsSetLimit is called, but are identical timestamps on
          //       subsequent calls, because blockDuration is set to 0 seconds in the process of clearing user and
          //       resource blocks on previous calls to testREsourceGetsBlockedWarnedIfExceedsSetLimit.
          case bl: UsageBlockingStatusChunk =>
            bl.usageBlockingStatus.size shouldBe 6
            resourcesToUnblock.foreach { resourceToUnblock =>
              bl.usageBlockingStatus.contains(resourceToUnblock._1) shouldBe true
              bl.usageBlockingStatus(resourceToUnblock._1).status.blockFrom.getOrElse(None) shouldBe bl.usageBlockingStatus(
                resourceToUnblock._1).status.blockTill.getOrElse(None)
              bl.usageBlockingStatus(resourceToUnblock._1).status.unblockFrom.isEmpty shouldBe true
              bl.usageBlockingStatus(resourceToUnblock._1).status.unblockTill.isEmpty shouldBe true
              bl.usageBlockingStatus(resourceToUnblock._1).resourcesStatus.size shouldBe 1
              bl.usageBlockingStatus(resourceToUnblock._1).resourcesStatus.contains(resourceToUnblock._2) shouldBe true
              blockDurationInSeconds(bl.usageBlockingStatus(resourceToUnblock._1).resourcesStatus(resourceToUnblock._2)) shouldBe resourceToUnblock._4
            }
        }
      }

      logger.debug("All expected blocks are in place! Attempting to remove blocks...")

      // Expect counts on all resources to be non-zero
      resourcesToUnblock.foreach { resourceToUnblock =>
        sendToResourceUsageTrackerRegion(resourceToUnblock._1, GetAllResourceUsages)
        expectMsgPF() {
          case ru: ResourceUsages if ru.usages.contains(resourceToUnblock._2) =>
            ru.usages(resourceToUnblock._2).foreach {
              case (bucket, bucketExt) =>
                logger.debug(s"Bucket $bucket's usedCount (${bucketExt.usedCount}) should be >= ${resourceToUnblock._3} but always < $maxMessages")
                bucketExt.usedCount should be >= resourceToUnblock._3
                bucketExt.usedCount should be < maxMessages
            }
        }
      }

      /* Unblock resources using BlockCaller with a block period of 0 seconds. Expect all resources to be unblocked
       * AND all counts for each resource to be reset/set to 0
       * */
      resourcesToUnblock.foreach { resourceToUnblock =>
        // Block resource resourceToUnblock._2 for caller resourceToUnblock._1 with a period of 0 to clear/delete the
        // block instead of unblock indefinitely
        singletonParentProxy ! ForResourceBlockingStatusMngr(BlockCaller(resourceToUnblock._1, blockPeriod=Option(0),
          allBlockedResources=Option("Y")))
        fishForMessage(10.seconds, hint="Failed to process all response messages from BlockCaller") {
          case rbl: CallerResourceBlocked if rbl.callerId.equals(resourceToUnblock._1) && rbl.blockPeriod == 0 => false
          case bl: CallerBlocked if bl.callerId.equals(resourceToUnblock._1) && bl.blockPeriod == 0 => true
          case _ => false
        }
      }

      // Expect no blocked resources
      eventually (timeout(Span(10, Seconds))) {
        // Give time for the system to process all of the BlockCaller messages. The sleep causes GetBlockedList
        // to be called once per second for up to 10 seconds.
        Thread.sleep(1000)
        singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked = false,
          onlyActive = true, inChunks = false))
        expectMsgPF() {
          case bl: UsageBlockingStatusChunk if bl.usageBlockingStatus.isEmpty =>
        }
      }

      // Expect counts on all resources to be zero
      resourcesToUnblock.foreach { resourceToUnblock =>
        sendToResourceUsageTrackerRegion(resourceToUnblock._1, GetAllResourceUsages)
        expectMsgPF() {
          case ru: ResourceUsages if ru.usages.contains(resourceToUnblock._2) =>
            ru.usages(resourceToUnblock._2).foreach {
              case (_, bucketExt) => bucketExt.usedCount shouldBe 0
            }
        }
      }

      // Test warned
      //
      // The following rule in resource-usage-rule-spec.conf (resource-usage-rule.conf file used for tests) causes
      // violation-action group 50 to call log-msg and warn-resource after the fifth iteration, when
      // allowed-counts of 5 is exceeded on bucket 300 (5 CREATE_MSG_connReq allowed within 300 seconds).
      //
      // 300: {"allowed-counts": 5, "violation-action-id": 50}
      //
      // ... where violation-action group 50 is defined as:
      //
      // # suspicious, log it
      // 50 {
      //   log-msg: {"level": "info"}
      //   warn-resource: {"track-by": "ip", "period": 600}
      // }
      //
      // Expect both a warning on the CREATE_MSG_connReq resource (warn-resource from violation-action group 50) and a
      // warning on the caller's IP (warn-user from violation-action group 70 (see "Test blocked" comment above)
      eventually {
        singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true, onlyUnwarned = false,
          onlyActive = true, inChunks = false))
        expectMsgPF() {
          case bl: UsageWarningStatusChunk if bl.usageWarningStatus.nonEmpty =>
        }
      }

      // Remove warning on the CREATE_MSG_connReq resource
      singletonParentProxy ! ForResourceWarningStatusMngr(UnwarnResourceForCaller(user1IpAddress,
        createMsgConnReq, Some(ZonedDateTime.now())))
      expectMsgPF() {
        case uu: CallerResourceUnwarned if uu.callerId == user1IpAddress && uu.resourceName == createMsgConnReq =>
      }
      // Expect the resource warning to be removed (resourcesStatus is empty), but the IP warning to remain
      // (status.warnFrom is non-empty and status.warnTill is empty)
      eventually {
        singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true, onlyUnwarned = false,
          onlyActive = true, inChunks = false))
        expectMsgPF() {
          case wl: UsageWarningStatusChunk if wl.usageWarningStatus.nonEmpty &&
            wl.usageWarningStatus.contains(user1IpAddress) &&
            wl.usageWarningStatus(user1IpAddress).status.warnFrom.nonEmpty &&
            wl.usageWarningStatus(user1IpAddress).status.warnTill.isEmpty &&
            wl.usageWarningStatus(user1IpAddress).status.unwarnFrom.isEmpty &&
            wl.usageWarningStatus(user1IpAddress).status.unwarnTill.isEmpty &&
            wl.usageWarningStatus(user1IpAddress).resourcesStatus.isEmpty =>
        }
      }

      singletonParentProxy ! ForResourceWarningStatusMngr(WarnCaller(user1IpAddress, warnPeriod=Option(0),
        allWarnedResources=Option("Y")))
      fishForMessage(10.seconds, hint="Failed to process all response messages from WarnCaller") {
        case w: CallerWarned if w.callerId.equals(user1IpAddress) && w.warnPeriod == 0 => true
        case _ => false
      }

      // Expect resource and caller warnings for caller user1IpAddress to be removed
      eventually {
        singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true, onlyUnwarned = false,
          onlyActive = true, inChunks = false))
        expectMsgPF() {
          case wl: UsageWarningStatusChunk if wl.usageWarningStatus.isEmpty =>
        }
      }

      resetResourceUsage()
    }

    def testResourceNotBlockingWarningIfCountIsResetAccordingly(): Unit = {
      (1 to 6).foreach { _ =>
        // Clear buckets that have "allowed-counts" of 6 or less (300 and -1).
        sendToResourceUsageTrackerRegion(user1IpAddress,
          UpdateResourcesUsageCounter(List(
            ResourceUsageCounterDetail(createMsgConnReq, 300, None),
            ResourceUsageCounterDetail(createMsgConnReq, BUCKET_ID_INDEFINITE_TIME, None)
          )))
        expectMsg(Done)

        sendToResourceUsageTracker(user1IpAddress, RESOURCE_TYPE_MESSAGE, createMsgConnReq, None)
        expectNoMessage()

        // Expect usageBlockingStatus and usageWarningStatus to continue to be empty
        singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked=false,
          onlyActive=true, inChunks = false))
        expectMsgPF() {
          case bl: UsageBlockingStatusChunk if bl.usageBlockingStatus.isEmpty =>
        }
        singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true, onlyUnwarned=false,
          onlyActive=true, inChunks = false))
        expectMsgPF() {
          case wl: UsageWarningStatusChunk if wl.usageWarningStatus.isEmpty =>
        }
      }
    }

    "ResourceUsageTracker" - {

      "when sent AddResourceUsage command for two different IP addresses" - {
        "should succeed and respond with no message (async)" in {
          sendToResourceUsageTracker(user1IpAddress, RESOURCE_TYPE_ENDPOINT, "resource1", Some(user1DID))
          expectNoMessage()
          sendToResourceUsageTracker(user2IpAddress, RESOURCE_TYPE_ENDPOINT, "resource1", Some(user2DID))
          expectNoMessage()
        }
      }

      "when sent GetResourceUsage command for global token" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(globalToken, GetResourceUsage("resource1"))
          expectMsgPF() {
            case ru: ResourceUsagesByBuckets if
            ru.usages.size == 3 && ru.usages.values.sum == 6 =>
          }
        }
      }

      "when sent GetResourceUsage command for user1 IP and user1 DID" - {
        "should respond with expected ResourceUsages" in {
          val ids = Option(user1IpAddress) ++ Option(user1DID)
          ids.foreach { id =>
            sendToResourceUsageTrackerRegion(id, tracking.GetResourceUsage("resource1"))
            expectMsgPF() {
              case ru: ResourceUsagesByBuckets if
              ru.usages.size == 3 && ru.usages.values.sum == 3 =>
            }
          }
        }
      }

      "when sent same AddResourceUsage commands again" - {
        "should respond with no message (async)" in {
          sendToResourceUsageTracker(user1IpAddress, RESOURCE_TYPE_ENDPOINT, "resource1", Some(user1DID))
          expectNoMessage()
          sendToResourceUsageTracker(user2IpAddress, RESOURCE_TYPE_ENDPOINT, "resource1", Some(user2DID))
          expectNoMessage()
        }
      }

      "when sent GetResourceUsage command for the global token again" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(globalToken, tracking.GetResourceUsage("resource1"))
          expectMsgPF() {
            case ru: ResourceUsagesByBuckets if
            ru.usages.size == 3 && ru.usages.values.sum == 12 =>
          }
        }
      }

      "when sent GetResourceUsage command again" - {
        "should respond with expected ResourceUsages" in {
          val ids = Option(user1IpAddress) ++ Option(user1DID)
          ids.foreach { id =>
            sendToResourceUsageTrackerRegion(id, tracking.GetResourceUsage("resource1"))
            expectMsgPF() {
              case ru: ResourceUsagesByBuckets if
              ru.usages.size == 3 && ru.usages.values.sum == 6 =>
            }
          }
        }
      }

      "when sent same AddResourceUsage command for another resource type" - {
        "should respond with no message (async)" in {
          sendToResourceUsageTracker(user1IpAddress, RESOURCE_TYPE_MESSAGE, createMsgConnReq, None)
          expectNoMessage()
          sendToResourceUsageTracker(user2IpAddress, RESOURCE_TYPE_MESSAGE, DUMMY_MSG, Some(user2DID))
          expectNoMessage()
        }
      }


      "when sent GetResourceUsage command for user1 IP for resource1 resource usages" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(user1IpAddress, tracking.GetResourceUsage("resource1"))
          expectMsgPF() {
            case ru: ResourceUsagesByBuckets if
            ru.usages.size == 3 && ru.usages.values.sum == 6 =>
          }
        }
      }

      "when sent GetResourceUsage command for user1 for " + createMsgConnReq + " message usages" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(user1IpAddress, tracking.GetResourceUsage(createMsgConnReq))
          expectMsgPF() {
            case ru: ResourceUsagesByBuckets if
            ru.usages.size == 4 && ru.usages.values.sum == 4 =>
          }
        }
      }

      "when sent GetResourceUsage command for user2 for " + DUMMY_MSG + " message usages" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(user2IpAddress, tracking.GetResourceUsage(DUMMY_MSG))
          expectMsgPF() {
            case ru: ResourceUsagesByBuckets if
            ru.usages.size == 4 && ru.usages.values.sum == 4 =>
          }
        }
      }

      "when sent GetAllResourceUsages command for user1 IP" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(user1IpAddress, GetAllResourceUsages)
          expectMsgPF() {
            case ru: ResourceUsages if ru.usages.contains("resource1") && ru.usages.contains(createMsgConnReq) =>
          }
        }
      }

      // Restart the actor (clears usage stats) and add one usage for CREATE_MSG_connReq for user1IpAddress
      "when sent GetResourceUsage command for user1 for CREATE_MSG_connReq message usages (after actor restart)" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(user1IpAddress, tracking.GetResourceUsage(createMsgConnReq), restartActorBefore = true)
          expectMsgPF() {
            case ru: ResourceUsagesByBuckets if
            ru.usages.size == 1 && ru.usages.values.sum == 1 && ru.usages.contains(-1) =>
          }
        }
      }

      // Make sure resource1 usage stats were only cleared for user1IpAddress by the previous test
      "when sent GetAllResourceUsages command for global token after " + user1IpAddress + " actor restart" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(globalToken, GetAllResourceUsages)
          expectMsgPF() {
            case ru: ResourceUsages if ru.usages.contains("resource1") &&
              ru.usages.contains(DUMMY_MSG) &&
              ru.usages.contains(createMsgConnReq) =>
          }
        }
      }

      "when sent GetAllResourceUsages command for user1 IP after " + user1IpAddress + " actor restart" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(user1IpAddress, GetAllResourceUsages)
          expectMsgPF() {
            case ru: ResourceUsages if !ru.usages.contains("resource1") &&
              !ru.usages.contains(DUMMY_MSG) &&
              ru.usages.contains(createMsgConnReq) =>
          }
        }
      }

      "when sent GetAllResourceUsages command for user1 DID after " + user1IpAddress + " actor restart" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(user1DID, GetAllResourceUsages)
          expectMsgPF() {
            case ru: ResourceUsages if ru.usages.contains("resource1") =>
          }
        }
      }

      "when sent GetAllResourceUsages command for user2 DID after " + user1IpAddress + " actor restart" - {
        "should respond with expected ResourceUsages" in {
          sendToResourceUsageTrackerRegion(user2DID, GetAllResourceUsages)
          expectMsgPF() {
            case ru: ResourceUsages if ru.usages.contains("resource1") && ru.usages.contains(DUMMY_MSG) =>
          }
        }
      }

      "when sent same AddResourceUsage commands enough times to create blocks/warnings" - {
        "should detect and clear several blocks and warnings, and respond with Done" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          (1 to 2).foreach { _ =>
            testResourceGetsBlockedWarnedIfExceedsSetLimit()
          }
        }
      }

      "when resource counts are set to zero before adding the same resource enough times to create a block/warning" - {
        "should clear counts and never block or warn, and respond with Done" in {
          testResourceNotBlockingWarningIfCountIsResetAccordingly()
        }
      }

      "when sent same AddResourceUsages command enough times to AGAIN create blocks/warnings AFTER setting counts to zero" - {
        "should AGAIN detect and clear several blocks and warnings, and respond with Done" taggedAs (UNSAFE_IgnoreAkkaEvents) in {
          (1 to 2).foreach { _ =>
            testResourceGetsBlockedWarnedIfExceedsSetLimit()
          }
        }
      }

      "when sent GetBlockedList command as the last test in the ResourceUsageTracker tests" - {
        "should respond with no resources/callers blocked" in {
          // Expect no blocked resources
          eventually(timeout(Span(10, Seconds))) {
            singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked = false,
              onlyActive = true, inChunks = false))
            expectMsgPF() {
              case bl: UsageBlockingStatusChunk if bl.usageBlockingStatus.isEmpty =>
            }
          }
        }
      }
    }

    "ResourceBlockingStatusMngr" - {

      "when sent GetBlockedList command" - {
        "should respond with no resources/callers blocked" in {
          // Expect no blocked resources
          eventually(timeout(Span(10, Seconds))) {
            singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked = false,
              onlyActive = true, inChunks = false))
            expectMsgPF() {
              case bl: UsageBlockingStatusChunk if bl.usageBlockingStatus.isEmpty =>
            }
          }
        }
      }

      s"when sent BlockCaller command for $user1IpAddress" - {
        "should respond with CallerBlocked" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(BlockCaller(user1IpAddress))
          expectMsgPF() {
            case ub: CallerBlocked if ub.callerId == user1IpAddress =>
          }
        }
      }

      "when sent GetBlockedList command" - {
        s"should respond with blocked list containing $user1IpAddress" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked=false,
            onlyActive=true, inChunks = false))
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
            bl.usageBlockingStatus.size == 1 &&
              bl.usageBlockingStatus.contains(user1IpAddress) =>
          }
          eventually {
            ResourceBlockingStatusMngrCache.getOnlyBlocked().size shouldBe 1
          }
        }
      }

      s"when sent BlockCaller command for $user2IpAddress with some blocking time" - {
        "should respond with CallerBlocked" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockCaller(user2IpAddress, blockPeriod = Option(600)))
          expectMsgPF() {
            case ub: CallerBlocked if ub.callerId == user2IpAddress && ub.blockPeriod==600 =>
          }
        }
      }

      "when sent GetBlockedList command again" - {
        s"should respond with blocked list containing $user2IpAddress" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked=false,
            onlyActive=true, inChunks = false))
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
            bl.usageBlockingStatus.size == 2 &&
              bl.usageBlockingStatus.contains(user2IpAddress) =>
          }
          eventually {
            ResourceBlockingStatusMngrCache.getOnlyBlocked().size shouldBe 2
          }
        }
      }

      s"when sent BlockResourceForCaller command for $user3IpAddress" - {
        "should respond with CallerResourceBlocked" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user3IpAddress, "resource1", Some(ZonedDateTime.now()), Some(60)))
          expectMsgPF() {
            case urb: CallerResourceBlocked if
            urb.callerId==user3IpAddress &&
              urb.resourceName=="resource1" &&
              urb.blockPeriod == 60 =>
          }
        }
      }

      s"when sent BlockResourceForCaller command for $user3IpAddress for two more resources" - {
        "should respond with CallerResourceBlocked" in {
          List("resource2", "resource3").foreach { rn =>
            singletonParentProxy ! ForResourceBlockingStatusMngr(
              BlockResourceForCaller(user3IpAddress, rn, Some(ZonedDateTime.now()), Some(60)))
            expectMsgPF() {
              case urb: CallerResourceBlocked if
              urb.callerId == user3IpAddress &&
                urb.resourceName == rn &&
                urb.blockPeriod == 60 =>
            }
          }
        }
      }

      "when sent GetBlockedList command" - {
        "should respond with blocked list containing user3 for resource1" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = false,
            onlyUnblocked=false, onlyActive=false, inChunks = true))
          expectMsgPF() {
            case rubd: UsageBlockingStatusChunk if
            rubd.usageBlockingStatus.size == 7 &&
              rubd.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource1") =>
          }
        }
      }

      s"when sent UnblockCaller command for $user3IpAddress" - {
        "should respond with CallerUnblocked" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(UnblockCaller(user3IpAddress, Some(ZonedDateTime.now())))
          expectMsgPF() {
            case uu: CallerUnblocked if uu.callerId == user3IpAddress  =>

          }
        }
      }

      s"when sent GetBlockedList command after unblocking $user3IpAddress" - {
        s"should respond with blocked list still containing $user3IpAddress because resource1 is still blocked" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true,
            onlyUnblocked=false, onlyActive=true, inChunks = false))
          expectMsgPF() {
            case rubd: UsageBlockingStatusChunk if rubd.usageBlockingStatus.contains(user3IpAddress)  =>
          }
        }
      }

      s"when sent UnblockResourceForCaller command for $user3IpAddress and resource1" - {
        "should respond with CallerResourceUnblocked" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(UnblockResourceForCaller(user3IpAddress,
            "resource1", Some(ZonedDateTime.now())))
          expectMsgPF() {
            case uu: CallerResourceUnblocked if uu.callerId == user3IpAddress && uu.resourceName == "resource1" =>
          }
        }
      }

      s"when sent GetBlockedList command after unblocking $user3IpAddress for resource1" - {
        s"should respond with blocked list with $user3IpAddress but without resource1" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true,
            onlyUnblocked=false, onlyActive=true, inChunks = false))
          expectMsgPF() {
            case rubd: UsageBlockingStatusChunk if
            rubd.usageBlockingStatus.size == 3 &&
              ! rubd.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource1") =>
          }
        }
      }

      s"when sent UnblockCaller command for $user3IpAddress for all resources" - {
        "should respond with CallerUnblocked" in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(UnblockCaller(user3IpAddress,
            Some(ZonedDateTime.now()), allBlockedResources = Option(YES)))
          expectMsgPF() {
            case uu: CallerUnblocked if uu.callerId == user3IpAddress =>
          }
        }
      }

      s"when sent GetBlockedList command after unblocking $user3IpAddress for all blocked resources" - {
        s"should respond with blocked list without $user3IpAddress" in {
          eventually {
            singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true,
              onlyUnblocked = false, onlyActive = true, inChunks = false))
            expectMsgPF() {
              case rubd: UsageBlockingStatusChunk if
              rubd.usageBlockingStatus.size == 2 &&
                !rubd.usageBlockingStatus.contains(user3IpAddress) =>
            }
          }
        }
      }

    }

    "ResourceWarningStatusMngr" - {

      s"when sent WarnCaller command for $user1IpAddress" - {
        "should respond with CallerWarned" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(WarnCaller(user1IpAddress))
          expectMsgPF() {
            case ub: CallerWarned if ub.callerId == user1IpAddress =>
          }
        }
      }

      "when sent GetWarnedList command" - {
        s"should respond with warned list containing $user1IpAddress" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true, onlyUnwarned=false,
            onlyActive=true, inChunks = false))
          expectMsgPF() {
            case bl: UsageWarningStatusChunk if
            bl.usageWarningStatus.size == 1 &&
              bl.usageWarningStatus.contains(user1IpAddress) =>
          }
          eventually {
            ResourceWarningStatusMngrCache.getOnlyWarned().size shouldBe 1
          }
        }
      }

      s"when sent WarnCaller command for $user2IpAddress with some warning time" - {
        "should respond with CallerWarned" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnCaller(user2IpAddress, warnPeriod = Option(600)))
          expectMsgPF() {
            case ub: CallerWarned if ub.callerId == user2IpAddress && ub.warnPeriod==600 =>
          }
        }
      }

      "when sent GetWarnedList command again" - {
        s"should respond with warned list containing $user2IpAddress" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true, onlyUnwarned=false,
            onlyActive=true, inChunks = false))
          expectMsgPF() {
            case bl: UsageWarningStatusChunk if
            bl.usageWarningStatus.size == 2 &&
              bl.usageWarningStatus.contains(user2IpAddress) =>
          }
          eventually {
            ResourceWarningStatusMngrCache.getOnlyWarned().size shouldBe 2
          }
        }
      }

      s"when sent WarnResourceForCaller command for $user3IpAddress" - {
        "should respond with CallerResourceWarned" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user3IpAddress, "resource1", Some(ZonedDateTime.now()), Some(60)))
          expectMsgPF() {
            case urb: CallerResourceWarned if
            urb.callerId==user3IpAddress &&
              urb.resourceName=="resource1" &&
              urb.warnPeriod == 60 =>
          }
        }
      }

      s"when sent WarnResourceForCaller command for $user3IpAddress for two more resources" - {
        "should respond with CallerResourceWarned" in {
          List("resource2", "resource3").foreach { rn =>
            singletonParentProxy ! ForResourceWarningStatusMngr(
              WarnResourceForCaller(user3IpAddress, rn, Some(ZonedDateTime.now()), Some(60)))
            expectMsgPF() {
              case urb: CallerResourceWarned if
              urb.callerId == user3IpAddress &&
                urb.resourceName == rn &&
                urb.warnPeriod == 60 =>
            }
          }
        }
      }

      "when sent GetWarnedList command" - {
        "should respond with warned list containing user3 for resource1" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = false,
            onlyUnwarned=false, onlyActive=false, inChunks = true))
          expectMsgPF() {
            case rubd: UsageWarningStatusChunk if
            rubd.usageWarningStatus.size == 3 &&
              rubd.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource1") =>
          }
        }
      }

      s"when sent UnwarnCaller command for $user3IpAddress" - {
        "should respond with CallerUnwarned" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(UnwarnCaller(user3IpAddress, Some(ZonedDateTime.now())))
          expectMsgPF() {
            case uu: CallerUnwarned if uu.callerId == user3IpAddress  =>

          }
        }
      }

      s"when sent GetWarnedList command after unwarning $user3IpAddress" - {
        s"should respond with warned list still containing $user3IpAddress as resource1 is still warned" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true,
            onlyUnwarned=false, onlyActive=true, inChunks = false))
          expectMsgPF() {
            case rubd: UsageWarningStatusChunk if rubd.usageWarningStatus.contains(user3IpAddress)  =>
          }
        }
      }

      s"when sent UnwarnResourceForCaller command for $user3IpAddress and resource1" - {
        "should respond with CallerResourceUnwarned" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(UnwarnResourceForCaller(user3IpAddress,
            "resource1", Some(ZonedDateTime.now())))
          expectMsgPF() {
            case uu: CallerResourceUnwarned if uu.callerId == user3IpAddress && uu.resourceName == "resource1" =>

          }
        }
      }

      s"when sent GetWarnedList command after unwarning $user3IpAddress for resource1" - {
        s"should respond with warned list with $user3IpAddress but without resource1" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true,
            onlyUnwarned=false, onlyActive=true, inChunks = false))
          expectMsgPF() {
            case rubd: UsageWarningStatusChunk if
            rubd.usageWarningStatus.size == 3 &&
              ! rubd.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource1") =>
          }
        }
      }

      s"when sent UnwarnCaller command for $user3IpAddress for all resources" - {
        "should respond with CallerUnwarned" in {
          singletonParentProxy ! ForResourceWarningStatusMngr(UnwarnCaller(user3IpAddress,
            Some(ZonedDateTime.now()), allWarnedResources = Option(YES)))
          expectMsgPF() {
            case uu: CallerUnwarned if uu.callerId == user3IpAddress =>
          }
        }
      }

      s"when sent GetWarnedList command after unwarning $user3IpAddress for all warned resources" - {
        s"should respond with warned list without $user3IpAddress" in {
          eventually {
            singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true,
              onlyUnwarned = false, onlyActive = true, inChunks = false))
            expectMsgPF() {
              case rubd: UsageWarningStatusChunk if
              rubd.usageWarningStatus.size == 2 &&
                !rubd.usageWarningStatus.contains(user3IpAddress) =>
            }
          }
        }
      }

    }

  }

  resourceUsageTrackerSpec()
}
