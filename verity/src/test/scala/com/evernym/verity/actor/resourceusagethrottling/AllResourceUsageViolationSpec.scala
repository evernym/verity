package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.CallerResourceBlocked
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.cluster_singleton.ForResourceBlockingStatusMngr
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.{BlockResourceForCaller, GetBlockedList, UsageBlockingStatusChunk}
import com.evernym.verity.actor.resourceusagethrottling.helper.{ResourceUsageRuleHelper, ResourceUsageRuleHelperExtension}
import com.evernym.verity.actor.resourceusagethrottling.tracking.{GetAllResourceUsages, ResourceUsages}
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.http.route_handlers.restricted.{ResourceUsageCounterDetail, UpdateResourcesUsageCounter}
import com.typesafe.config.{Config, ConfigValueFactory}
import org.scalatest.time.{Seconds, Span}

import scala.collection.JavaConverters._

class AllResourceUsageViolationSpec extends BaseResourceUsageTrackerSpec {

  val ipAddress = "127.5.0.5"

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
  lazy val resourceUsageRuleHelper: ResourceUsageRuleHelper = ResourceUsageRuleHelperExtension(platform.actorSystem).get()
  val baseConfig: Config = appConfig.config

  override def beforeAll(): Unit = {
    super.beforeAll()
    appConfig.setConfig(customizeConfig(appConfig.config))
    resourceUsageRuleHelper.loadResourceUsageRules(appConfig.config)
  }

  override def afterAll(): Unit = {
    try {
      appConfig.setConfig(baseConfig)
      resourceUsageRuleHelper.loadResourceUsageRules(appConfig.config)
    } finally {
      super.afterAll()
    }
  }

  private def customizeConfig(config: Config): Config = {
    config
      .withValue(
        "verity.resource-usage-rules.usage-rules.default",
        ConfigValueFactory.fromMap(Map(
          "endpoint" -> Map(
            "all" -> Map(
              "600" -> Map(
                "allowed-counts" -> "5",
                "violation-action-id" -> "101",
              ).asJava,
            ).asJava,
          ).asJava,
          "message" -> Map(
            "all" -> Map(
              "300" -> Map(
                "allowed-counts" -> "3",
                "violation-action-id" -> "101",
              ).asJava,
            ).asJava,
          ).asJava,
        ).asJava))
  }

  def resetAllResourceUsage(): Unit = {
    List(RESOURCE_NAME_ENDPOINT_ALL, RESOURCE_NAME_MESSAGE_ALL).foreach { resourceName =>
      List(ipAddress, ENTITY_ID_GLOBAL).foreach { entityId =>
        sendToResourceUsageTrackerRegion(entityId,
          UpdateResourcesUsageCounter(List(
            ResourceUsageCounterDetail(resourceName, 300, None),
            ResourceUsageCounterDetail(resourceName, 600, None),
          )))
        expectMsg(Done)
      }
    }
  }

  def sendToResourceUsageTrackerAndWaitABit(resourceType: ResourceType,
                                            resourceName: ResourceName,
                                            ipAddress: IpAddress,
                                            userIdOpt: Option[UserId]): Unit = {
    sendToResourceUsageTracker(resourceType, resourceName, ipAddress, userIdOpt, resourceUsageRuleHelper.resourceUsageRules)
    expectNoMessage()
    Thread.sleep(100)
  }

  def testAllResourceGetsBlockedIfExceedsSetLimit(): Unit = {
    // Clear all buckets on preparation for this test
    resetAllResourceUsage()

    sendToResourceUsageTrackerAndWaitABit(RESOURCE_TYPE_ENDPOINT, "GET_agency", ipAddress, None)
    sendToResourceUsageTrackerAndWaitABit(RESOURCE_TYPE_ENDPOINT, "GET_agency_invite", ipAddress, None)
    sendToResourceUsageTrackerAndWaitABit(RESOURCE_TYPE_ENDPOINT, "GET_agency_invite_aries", ipAddress, None)
    sendToResourceUsageTrackerAndWaitABit(RESOURCE_TYPE_ENDPOINT, "GET_agency_invite_did", ipAddress, None)
    sendToResourceUsageTrackerAndWaitABit(RESOURCE_TYPE_ENDPOINT, "POST_agency_msg", ipAddress, None)
    val endpointAllError = intercept[BadRequestErrorException] {
      sendToResourceUsageTrackerAndWaitABit(RESOURCE_TYPE_ENDPOINT, "POST_agency_sms", ipAddress, None)
    }

    assert(endpointAllError.respCode.equals("GNR-123") && endpointAllError.getMessage.equals("usage blocked"))

    sendToResourceUsageTrackerAndWaitABit(RESOURCE_TYPE_MESSAGE, "agent-provisioning/CREATE_AGENT", ipAddress, None)
    sendToResourceUsageTrackerAndWaitABit(RESOURCE_TYPE_MESSAGE, "CREATE_KEY", ipAddress, None)
    sendToResourceUsageTrackerAndWaitABit(RESOURCE_TYPE_MESSAGE, "connecting/CREATE_MSG_connReq", ipAddress, None)
    val messageAllError = intercept[BadRequestErrorException] {
      sendToResourceUsageTrackerAndWaitABit(RESOURCE_TYPE_MESSAGE, "connecting/CREATE_MSG_connReqAnswer", ipAddress, None)
    }

    assert(messageAllError.respCode.equals("GNR-123") && messageAllError.getMessage.equals("usage blocked"))

    // Give time for the system to process all of the AddResourceUsage messages
    eventually (timeout(Span(10, Seconds)), interval(Span(1, Seconds))) {
      singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked = false,
        onlyActive = true, inChunks = false))
      expectMsgPF() {
        case bl: UsageBlockingStatusChunk =>
          bl.usageBlockingStatus.size shouldBe 1
          bl.usageBlockingStatus.contains(ipAddress) shouldBe true
          bl.usageBlockingStatus(ipAddress).resourcesStatus.size shouldBe 2
          List(RESOURCE_NAME_ENDPOINT_ALL, RESOURCE_NAME_MESSAGE_ALL).foreach { resourceName =>
            bl.usageBlockingStatus(ipAddress).resourcesStatus.contains(resourceName) shouldBe true
            blockDurationInSeconds(bl.usageBlockingStatus(ipAddress).resourcesStatus(resourceName)) shouldBe 60
          }
      }
    }

    // All expected blocks are in place! Attempting to remove blocks...

    // Expect counts on all resources to be non-zero
    sendToResourceUsageTrackerRegion(ipAddress, GetAllResourceUsages)
    expectMsgPF() {
      case ru: ResourceUsages =>
        ru.usages.contains(RESOURCE_NAME_ENDPOINT_ALL) shouldBe true
        ru.usages(RESOURCE_NAME_ENDPOINT_ALL).size shouldBe 1
        ru.usages(RESOURCE_NAME_ENDPOINT_ALL).contains("600") shouldBe true
        ru.usages(RESOURCE_NAME_ENDPOINT_ALL)("600").usedCount shouldBe 5

        ru.usages.contains(RESOURCE_NAME_MESSAGE_ALL) shouldBe true
        ru.usages(RESOURCE_NAME_MESSAGE_ALL).size shouldBe 1
        ru.usages(RESOURCE_NAME_MESSAGE_ALL).contains("300") shouldBe true
        ru.usages(RESOURCE_NAME_MESSAGE_ALL)("300").usedCount shouldBe 3
    }

    /* Unblock resources using BlockResourceForCaller with a block period of 0 seconds.
     * Expect all resources to be unblocked AND all counts for each resource to be reset/set to 0
     * */
    List(RESOURCE_NAME_ENDPOINT_ALL, RESOURCE_NAME_MESSAGE_ALL).foreach { resourceName =>
      // Block resource with a period of 0 to clear/delete the block instead of unblock indefinitely
      singletonParentProxy ! ForResourceBlockingStatusMngr(
        BlockResourceForCaller(ipAddress, resourceName, blockPeriod=Option(0)))
      expectMsgPF() {
        case rbl: CallerResourceBlocked =>
          rbl.callerId shouldBe ipAddress
          rbl.resourceName shouldBe resourceName
          rbl.blockPeriod shouldBe 0
      }
    }

    // Expect no blocked resources
    eventually (timeout(Span(10, Seconds)), interval(Span(1, Seconds))) {
      singletonParentProxy ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = true, onlyUnblocked = false,
        onlyActive = true, inChunks = false))
      expectMsgPF() {
        case bl: UsageBlockingStatusChunk => bl.usageBlockingStatus.isEmpty shouldBe true
      }
    }

    // Expect counts on all resources to be zero
    sendToResourceUsageTrackerRegion(ipAddress, GetAllResourceUsages)
    expectMsgPF() {
      case ru: ResourceUsages =>
        List(RESOURCE_NAME_ENDPOINT_ALL, RESOURCE_NAME_MESSAGE_ALL).foreach { resourceName =>
          ru.usages.get(resourceName).map { buckets =>
            buckets.foreach {
              case (_, bucketExt) => bucketExt.usedCount shouldBe 0
            }
          }
        }
    }

    // Clear all buckets on finalization for this test
    resetAllResourceUsage()
  }

  "AllResourceUsageViolation" - {
    "when AddResourceUsage commands are sent enough times to block endpoint.all or message.all resource" - {
      "corresponding resource should be blocked" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        testAllResourceGetsBlockedIfExceedsSetLimit()
      }
    }
  }

}
