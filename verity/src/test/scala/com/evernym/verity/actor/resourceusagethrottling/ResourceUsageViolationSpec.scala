package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.cluster_singleton._
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking._
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning._
import com.evernym.verity.actor.node_singleton.{ResourceBlockingStatusMngrCache, ResourceWarningStatusMngrCache}
import com.evernym.verity.actor.resourceusagethrottling.tracking._
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor._
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.route_handlers.restricted.{ResourceUsageCounterDetail, UpdateResourcesUsageCounter}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger
import org.scalatest.time.{Seconds, Span}

import java.time.ZonedDateTime
import scala.concurrent.duration._

class ResourceUsageViolationSpec
  extends BaseResourceUsageTrackerSpec {

  val logger: Logger = getLoggerByClass(classOf[ResourceUsageViolationSpec])

  val DUMMY_MSG = "DUMMY_MSG"

  val globalToken = "global"

  val user1IpAddress = "127.1.0.1"
  val user2IpAddress = "127.2.0.2"
  val user3IpAddress = "127.3.0.3"
  val user4IpAddress = "127.4.0.4"

  val user1DID = OWNER_ID_PREFIX + CommonSpecUtil.generateNewDid().DID
  val user2DID = OWNER_ID_PREFIX + CommonSpecUtil.generateNewDid().DID
  val user3DID = COUNTERPARTY_ID_PREFIX + CommonSpecUtil.generateNewDid().DID
  val user4DID = COUNTERPARTY_ID_PREFIX + CommonSpecUtil.generateNewDid().DID

  val createMsgConnReq: String = MSG_TYPE_CREATE_MSG + "_" + CREATE_MSG_TYPE_CONN_REQ

  def resourceUsageTrackerSpec() {

    // Begin resources and helper functions used in the testResourceGetsBlockedWarnedIfExceedsSetLimit test
    val resourceUsageParams: List[ResourceUsageParam] = List(
      ResourceUsageParam(createMsgConnReq, Option(user1IpAddress), None, Some(3)),
      ResourceUsageParam(DUMMY_MSG, Option(user2IpAddress), Some(user2DID), Some(2)),
      ResourceUsageParam(DUMMY_MSG, Option(user4IpAddress), Some(user4DID), Some(2))
    )

    // Reset counters for all resources involved in this test
    // expected used count after looping 6 times above.
    val expectedBlockedResources: List[BlockedResourcesParam] = List(
      BlockedResourcesParam(user1IpAddress, createMsgConnReq, 2, 600),
      BlockedResourcesParam(user2IpAddress, DUMMY_MSG, 3, 60),
      BlockedResourcesParam(user2DID, DUMMY_MSG, 3, 120),
      BlockedResourcesParam(user4IpAddress, DUMMY_MSG, 3, 60),
      BlockedResourcesParam(user4DID, DUMMY_MSG, 3, 120),
      BlockedResourcesParam(globalToken, DUMMY_MSG, 4, 180)
    )

    def resetResourceUsage(): Unit = {
      resourceUsageParams.foreach { rup =>
        // Clear all buckets in preparation for this test
        // Previous tests may have already added stats to each of the following buckets (300, 600, ..., etc).
        // CREATE_MSG_connReq
        (Option(ENTITY_ID_GLOBAL) ++ rup.ipAddress ++ rup.userId).foreach { entityId =>
          sendToResourceUsageTrackerRegion(entityId,
            UpdateResourcesUsageCounter(List(
              ResourceUsageCounterDetail(rup.resourceName, 300, None),
              ResourceUsageCounterDetail(rup.resourceName, 600, None),
              ResourceUsageCounterDetail(rup.resourceName, 1200, None),
              ResourceUsageCounterDetail(rup.resourceName, 1800, None),
              ResourceUsageCounterDetail(rup.resourceName, BUCKET_ID_INDEFINITE_TIME, None)
            )))
          expectMsg(Done)
        }
      }
    }
    // End resources and helper functions used in the testResourceGetsBlockedWarnedIfExceedsSetLimit test

    def testResourceGetsBlockedWarnedIfExceedsSetLimit(): Unit = {
      resetResourceUsage()
      val maxMessages = 6
      (1 to maxMessages).foreach { i =>
        resourceUsageParams.foreach { rup =>
          try {
            logger.debug(s"Adding resource usage for resourceName: ${rup.resourceName} and IP: ${rup.ipAddress} and user DID: ${rup.userId}")
            sendToResourceUsageTracker(RESOURCE_TYPE_MESSAGE, rup.resourceName, rup.ipAddress, rup.userId)
            expectNoMessage()
          } catch {
            case e: BadRequestErrorException =>
              logger.warn(s"Usage blocked resource: ${rup.resourceName} for IP: ${rup.ipAddress} and/or user DID: ${rup.userId}")
              assert(i >= rup.blockAfterIterationCount.getOrElse(-1) && e.respCode.equals("GNR-123") && e.getMessage.equals("usage blocked"))
            case _: Exception =>
              fail("Unhandled exception encountered while adding resource usage stats: entityId: " +
                rup.ipAddress + " resourceType: " + RESOURCE_TYPE_MESSAGE + " resourceName: " + rup.resourceName)
          }
          // Yield to other threads between calls to sendToResourceUsageTrackerRegionAddResourceUsage.
          // Don't overwhelm the resource usage tracker during this test so that numbers are predictable. It is
          // expected for limits to possibly exceeded thresholds, because of async processing and analyzing of
          // usage counts.
          Thread.sleep(10)
        }
      }

      // Test blocked
      //
      // The following rule in resource-usage-rule-spec.conf (resource-usage-rule.conf file used for tests) causes
      // violation-action group 70 to call log-msg, warn-entity, and block-resource after the second iteration; when
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
      //   warn-entity: {"entity-types": "ip", "period": -1}
      //   block-resource: {"entity-types": "ip", "period": 600}
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
      //   log-msg: {"entity-types": "ip", "level": "info"}
      //   # Block if and only if entityId is an IP address
      //   block-resource: {"entity-types": "ip", "period": 60}
      // }
      //
      // 102 {
      //   # Log only at trace level if and only if entityId is a DID (21 to 23 length)
      //   log-msg: {"entity-types": "user", "level": "debug"}
      //   block-resource: {"entity-types": "user", "period": 120}
      // }
      //
      // 103 {
      //   # Log only at info level if and only if entityId is "global"
      //   log-msg: {"entity-types": "global", "level": "trace"}
      //   # Block if and only if entityId is "global"
      //   block-resource: {"entity-types": "global", "period": 180}
      // }
      //
      // Expect the DUMMY_MSG resource to be blocked for user2IpAddress (action 101), user2DID (action 102),
      // and "global" (action 103)

      // Give time for the system to process all of the AddResourceUsage messages. The sleep causes GetBlockedList
      // to be called once per second for up to 10 seconds.
      eventually (timeout(Span(10, Seconds)), interval(Span(1, Seconds))) {
        singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked = false,
          onlyActive = true, inChunks = false))
        expectMsgPF() {
          // Note: status.blockFrom and status.blockTill should always be equal.
          // Details:
          //       status.blockFrom and status.blockTill are "Empty" the first time
          //       testResourceGetsBlockedWarnedIfExceedsSetLimit is called, but are identical timestamps on
          //       subsequent calls, because blockDuration is set to 0 seconds in the process of clearing user and
          //       resource blocks on previous calls to testResourceGetsBlockedWarnedIfExceedsSetLimit.
          case bl: UsageBlockingStatusChunk =>
            bl.usageBlockingStatus.size shouldBe expectedBlockedResources.size
            expectedBlockedResources.foreach { rup =>
              bl.usageBlockingStatus.contains(rup.entityId) shouldBe true
              bl.usageBlockingStatus(rup.entityId).status.blockFrom.getOrElse(None) shouldBe bl.usageBlockingStatus(
                rup.entityId).status.blockTill.getOrElse(None)
              bl.usageBlockingStatus(rup.entityId).status.unblockFrom.isEmpty shouldBe true
              bl.usageBlockingStatus(rup.entityId).status.unblockTill.isEmpty shouldBe true
              bl.usageBlockingStatus(rup.entityId).resourcesStatus.size shouldBe 1
              bl.usageBlockingStatus(rup.entityId).resourcesStatus.contains(rup.resourceName) shouldBe true
              blockDurationInSeconds(bl.usageBlockingStatus(rup.entityId).resourcesStatus(rup.resourceName)) shouldBe rup.blockDurInSeconds
            }
        }
      }

      logger.debug("All expected blocks are in place! Attempting to remove blocks...")

      // Expect counts on all resources to be non-zero
      expectedBlockedResources.foreach { rup =>
        sendToResourceUsageTrackerRegion(rup.entityId, GetAllResourceUsages)
        expectMsgPF() {
          case ru: ResourceUsages if ru.usages.contains(rup.resourceName) =>
            ru.usages(rup.resourceName).foreach {
              case (bucket, bucketExt) =>
                logger.debug(s"Bucket $bucket's usedCount (${bucketExt.usedCount}) should be >= ${rup.expectedUsedCount} but always < $maxMessages")
                bucketExt.usedCount should be >= rup.expectedUsedCount
                bucketExt.usedCount should be < maxMessages
            }
        }
      }

      /* Unblock resources using BlockCaller with a block period of 0 seconds. Expect all resources to be unblocked
       * AND all counts for each resource to be reset/set to 0
       * */
      expectedBlockedResources.foreach { rup =>
        // Block resource resourceToUnblock._2 for caller resourceToUnblock._1 with a period of 0 to clear/delete the
        // block instead of unblock indefinitely
        singletonParentProxy ! ForResourceBlockingStatusMngr(BlockCaller(rup.entityId, blockPeriod=Option(0),
          allBlockedResources=Option("Y")))
        fishForMessage(10.seconds, hint="Failed to process all response messages from BlockCaller") {
          case rbl: CallerResourceBlocked if rbl.callerId.equals(rup.entityId) && rbl.blockPeriod == 0 => false
          case bl: CallerBlocked if bl.callerId.equals(rup.entityId) && bl.blockPeriod == 0 => true
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
      expectedBlockedResources.foreach { rup =>
        sendToResourceUsageTrackerRegion(rup.entityId, GetAllResourceUsages)
        expectMsgPF() {
          case ru: ResourceUsages if ru.usages.contains(rup.resourceName) =>
            ru.usages(rup.resourceName).foreach {
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
      //   warn-resource: {"entity-types": "ip", "period": 600}
      // }
      //
      // Expect both a warning on the CREATE_MSG_connReq resource (warn-resource from violation-action group 50) and a
      // warning on the caller's IP (warn-entity from violation-action group 70 (see "Test blocked" comment above)
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

        sendToResourceUsageTracker(RESOURCE_TYPE_MESSAGE, createMsgConnReq, Option(user1IpAddress), None)
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
          sendToResourceUsageTracker(RESOURCE_TYPE_ENDPOINT, "resource1", Option(user1IpAddress), Some(user1DID))
          expectNoMessage()
          sendToResourceUsageTracker(RESOURCE_TYPE_ENDPOINT, "resource1", Option(user2IpAddress), Some(user2DID))
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
          sendToResourceUsageTracker(RESOURCE_TYPE_ENDPOINT, "resource1", Option(user1IpAddress), Some(user1DID))
          expectNoMessage()
          sendToResourceUsageTracker(RESOURCE_TYPE_ENDPOINT, "resource1", Option(user2IpAddress), Some(user2DID))
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
          sendToResourceUsageTracker(RESOURCE_TYPE_MESSAGE, createMsgConnReq, Option(user1IpAddress), None)
          expectNoMessage()
          sendToResourceUsageTracker(RESOURCE_TYPE_MESSAGE, DUMMY_MSG, Option(user2IpAddress), Some(user2DID))
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
            rubd.usageBlockingStatus.size == expectedBlockedResources.size + 1 &&
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

case class ResourceUsageParam(resourceName: String,
                              ipAddress: Option[IpAddress],
                              userId: Option[UserId],
                              blockAfterIterationCount: Option[Int])

case class BlockedResourcesParam(entityId: EntityId,
                                 resourceName: ResourceName,
                                 expectedUsedCount: Int,
                                 blockDurInSeconds: Int)
