package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.actor._
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.cluster_singleton._
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking._
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning._
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageRuleHelperExtension
import com.evernym.verity.actor.resourceusagethrottling.tracking._
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.route_handlers.restricted.{ResourceUsageCounterDetail, UpdateResourcesUsageCounter}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger
import org.scalatest.time.{Seconds, Span}
import com.evernym.verity.util2.ExecutionContextProvider


class ResourceUsageViolationSpec
  extends BaseResourceUsageTrackerSpec {

  val logger: Logger = getLoggerByClass(classOf[ResourceUsageViolationSpec])

  val DUMMY_MSG = "dummy-family/DUMMY_MSG"

  val globalToken = "global"

  val user1IpAddress = "127.1.0.1"
  val user2IpAddress = "127.2.0.2"
  val user3IpAddress = "127.3.0.3"
  val user4IpAddress = "127.4.0.4"

  val user1DID = OWNER_ID_PREFIX + CommonSpecUtil.generateNewDid().did
  val user2DID = OWNER_ID_PREFIX + CommonSpecUtil.generateNewDid().did
  val user3DID = COUNTERPARTY_ID_PREFIX + CommonSpecUtil.generateNewDid().did
  val user4DID = COUNTERPARTY_ID_PREFIX + CommonSpecUtil.generateNewDid().did

  val createMsgConnReq: String = MSG_FAMILY_CONNECTING + "/" + MSG_TYPE_CREATE_MSG + "_" + CREATE_MSG_TYPE_CONN_REQ

  def resourceUsageTrackerSpec() {
    val resourceUsageRules = ResourceUsageRuleHelperExtension(system).get().resourceUsageRules

    // Begin resources and helper functions used in the testResourceGetsBlockedWarnedIfExceedsSetLimit test
    val resourceUsageParams: List[ResourceUsageParam] = List(
      ResourceUsageParam(createMsgConnReq, user1IpAddress, None, Some(3)),
      ResourceUsageParam(DUMMY_MSG, user2IpAddress, Some(user2DID), Some(2)),
      ResourceUsageParam(DUMMY_MSG, user4IpAddress, Some(user4DID), Some(2))
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
        // connecting/CREATE_MSG_connReq
        (Option(rup.ipAddress) ++ rup.userId ++ Option(ENTITY_ID_GLOBAL)).foreach { entityId =>
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
            sendToResourceUsageTracker(RESOURCE_TYPE_MESSAGE, rup.resourceName, rup.ipAddress, rup.userId, resourceUsageRules)
            expectNoMessage()
          } catch {
            case e: BadRequestErrorException =>
              logger.warn(s"Usage blocked resource: ${rup.resourceName} for IP: ${rup.ipAddress} and/or user DID: ${rup.userId}")
              assert(i >= rup.blockAfterIterationCount.getOrElse(-1) && e.respCode.equals("GNR-123") && e.getMessage.equals("usage blocked"))
            case _: Exception =>
              fail("Unhandled exception encountered while adding resource usage stats: entityId: " +
                rup.ipAddress + " resourceType: " + RESOURCE_TYPE_MESSAGE + " resourceName: " + rup.resourceName)
          }
          // Yield to other threads between calls to sendToResourceUsageTracker.
          // Don't overwhelm the resource usage tracker during this test so that numbers are predictable. It is
          // expected for limits to possibly exceeded thresholds, because of async processing and analyzing of
          // usage counts.
          Thread.sleep(10)
        }
      }

      // Test blocked
      //
      // The following rule in resource-usage-rule.conf (resource-usage-rule.conf file used for tests) causes
      // violation-action group 70 to call log-msg, warn-entity, and block-resource after the second iteration; when
      // allowed-counts of 2 is exceeded on bucket -1 (infinite bucket).
      //
      // "connecting/CREATE_MSG_connReq" {
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
      // Expect the connecting/CREATE_MSG_connReq resource to be blocked for user1IpAddress
      //
      // The following rules in resource-usage-rule.conf causes violation-action groups 100 - 103 to fire after
      // the third iteration (100-102 on the 4th iteration and 103 on the 5th iteration)
      //
      // "dummy-family/DUMMY_MSG" {
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
      // Expect the dummy-family/DUMMY_MSG resource to be blocked for user2IpAddress (action 101),
      // user2DID (action 102), and "global" (action 103)

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

      /* Unblock resources using BlockCaller with a block period of 0 seconds and allBlockedResources flag set to Y.
       * Expect all resources to be unblocked AND all counts for each resource to be reset/set to 0.
       * */
      expectedBlockedResources.foreach { rup =>
        singletonParentProxy ! ForResourceBlockingStatusMngr(BlockCaller(rup.entityId, blockPeriod=Some(0),
          allBlockedResources=Some("Y")))
        expectMsgPF() {
          case ub: CallerBlocked =>
            ub.callerId shouldBe rup.entityId
            ub.blockPeriod shouldBe 0
        }
      }

      // Expect no blocked entities and resources
      eventually (timeout(Span(10, Seconds))) {
        // Give time for the system to process all of the BlockCaller messages. The sleep causes GetBlockedList
        // to be called once per second for up to 10 seconds.
        Thread.sleep(1000)
        singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked = false,
          onlyActive = true, inChunks = false))
        expectMsgPF() {
          case bl: UsageBlockingStatusChunk => bl.usageBlockingStatus.isEmpty shouldBe true
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
      // The following rule in resource-usage-rule.conf (resource-usage-rule.conf file used for tests) causes
      // violation-action group 70 to call log-msg, warn-entity and block-resource after the second iteration, when
      // allowed-counts of 2 is exceeded on bucket -1 (infinite bucket)
      // (2 connecting/CREATE_MSG_connReq allowed for lifetime).
      // (Block from the resource for the entity was already removed earlier.)
      //
      // -1: {"allowed-counts": 2, "violation-action-id": 70, "persist-usage-state": true}
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
      // Expect a warning on the caller's IP
      eventually {
        singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true, onlyUnwarned = false,
          onlyActive = true, inChunks = false))
        expectMsgPF() {
          case wl: UsageWarningStatusChunk =>
            wl.usageWarningStatus.contains(user1IpAddress) shouldBe true
            wl.usageWarningStatus(user1IpAddress).status.warnFrom.nonEmpty shouldBe true
            wl.usageWarningStatus(user1IpAddress).status.warnTill.isEmpty shouldBe true
            wl.usageWarningStatus(user1IpAddress).status.unwarnFrom.isEmpty shouldBe true
            wl.usageWarningStatus(user1IpAddress).status.unwarnTill.isEmpty shouldBe true
            wl.usageWarningStatus(user1IpAddress).resourcesStatus.isEmpty shouldBe true
        }
      }

      /* Unwarn the caller's IP using WarnCaller with a warn period of 0 seconds and allWarnedResources flag set to Y.
       * Expect the caller's IP to be unwarned.
       * */
      singletonParentProxy ! ForResourceWarningStatusMngr(WarnCaller(user1IpAddress, warnPeriod=Some(0),
        allWarnedResources=Some("Y")))
      expectMsgPF() {
        case uw: CallerWarned =>
          uw.callerId shouldBe user1IpAddress
          uw.warnPeriod shouldBe 0
      }

      // Expect no warned entities and resources
      eventually {
        singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true, onlyUnwarned = false,
          onlyActive = true, inChunks = false))
        expectMsgPF() {
          case wl: UsageWarningStatusChunk => wl.usageWarningStatus.isEmpty shouldBe true
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

        sendToResourceUsageTracker(RESOURCE_TYPE_MESSAGE, createMsgConnReq, user1IpAddress, None, resourceUsageRules)
        expectNoMessage()

        // Expect usageBlockingStatus and usageWarningStatus to continue to be empty
        singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked=false,
          onlyActive=true, inChunks = false))
        expectMsgPF() {
          case bl: UsageBlockingStatusChunk => bl.usageBlockingStatus.isEmpty shouldBe true
        }
        singletonParentProxy ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = true, onlyUnwarned=false,
          onlyActive=true, inChunks = false))
        expectMsgPF() {
          case wl: UsageWarningStatusChunk => wl.usageWarningStatus.isEmpty shouldBe true
        }
      }
    }

    "ResourceUsageTracker" - {

      "when sent AddResourceUsage command for two different IP addresses" - {
        "should succeed and respond with no message (async)" in {
          sendToResourceUsageTracker(RESOURCE_TYPE_ENDPOINT, "resource1", user1IpAddress, Some(user1DID), resourceUsageRules)
          expectNoMessage()
          sendToResourceUsageTracker(RESOURCE_TYPE_ENDPOINT, "resource1", user2IpAddress, Some(user2DID), resourceUsageRules)
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
          sendToResourceUsageTracker(RESOURCE_TYPE_ENDPOINT, "resource1", user1IpAddress, Some(user1DID), resourceUsageRules)
          expectNoMessage()
          sendToResourceUsageTracker(RESOURCE_TYPE_ENDPOINT, "resource1", user2IpAddress, Some(user2DID), resourceUsageRules)
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
          sendToResourceUsageTracker(RESOURCE_TYPE_MESSAGE, createMsgConnReq, user1IpAddress, None, resourceUsageRules)
          expectNoMessage()
          sendToResourceUsageTracker(RESOURCE_TYPE_MESSAGE, DUMMY_MSG, user2IpAddress, Some(user2DID), resourceUsageRules)
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

      // Restart the actor (clears usage stats) and add one usage for connecting/CREATE_MSG_connReq for user1IpAddress
      "when sent GetResourceUsage command for user1 for connecting/CREATE_MSG_connReq message usages (after actor restart)" - {
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

  }

  resourceUsageTrackerSpec()

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

case class ResourceUsageParam(resourceName: String,
                              ipAddress: IpAddress,
                              userId: Option[UserId],
                              blockAfterIterationCount: Option[Int])

case class BlockedResourcesParam(entityId: EntityId,
                                 resourceName: ResourceName,
                                 expectedUsedCount: Int,
                                 blockDurInSeconds: Int)
