package com.evernym.verity.actor.resourceusagethrottling.helper

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpec


class ResourceUsageRuleHelperSpec extends BasicSpec {
  lazy val appConfig: AppConfig = new TestAppConfig()
  lazy val resourceUsageRuleHelper: ResourceUsageRuleHelper = new ResourceUsageRuleHelper(appConfig.config)

  "ResourceUsageRuleHelper" - {

    "when initialized" - {
      "api usage rules should be loaded" in {
        resourceUsageRuleHelper.loadResourceUsageRules(appConfig.config)
        resourceUsageRuleHelper.resourceUsageRules shouldBe getExpectedResourceUsageRule
      }
    }

    "when called get default usage rule name for a token" - {
      "should respond with default usage rule name based on the token type" in {
        resourceUsageRuleHelper.getDefaultRuleNameByEntityId("global") shouldBe "global"
        resourceUsageRuleHelper.getDefaultRuleNameByEntityId("191.0.0.4") shouldBe "ip-address"
        resourceUsageRuleHelper.getDefaultRuleNameByEntityId("owner-JQAq9L8yF9HUh2qWcigvcs") shouldBe "user-id-owner"
        resourceUsageRuleHelper.getDefaultRuleNameByEntityId("owner-AV2qY9vwvYjthGPeFvipanFdkHGt5CmoCNNAFvAfNuQg") shouldBe "user-id-owner"
        resourceUsageRuleHelper.getDefaultRuleNameByEntityId("counterparty-VLDLAz68D7DVTi6kzrKnaB") shouldBe "user-id-counterparty"
        resourceUsageRuleHelper.getDefaultRuleNameByEntityId("counterparty-GScBzgJ2e81HH9apA1SdCi3gk6MgTXLpUnQAMYMtB2qY") shouldBe "user-id-counterparty"
        resourceUsageRuleHelper.getDefaultRuleNameByEntityId("randomToken") shouldBe "default"
        resourceUsageRuleHelper.getDefaultRuleNameByEntityId("128.0.0.1") shouldBe "ip-address"
      }
    }

    "when called get usage rule name for a token" - {
      "should respond with applicable usage rule name" in {
        resourceUsageRuleHelper.getRuleNameByEntityId("global") shouldBe "default"
        resourceUsageRuleHelper.getRuleNameByEntityId("191.0.0.4") shouldBe "default"
        resourceUsageRuleHelper.getRuleNameByEntityId("owner-JQAq9L8yF9HUh2qWcigvcs") shouldBe "default"
        resourceUsageRuleHelper.getRuleNameByEntityId("owner-AV2qY9vwvYjthGPeFvipanFdkHGt5CmoCNNAFvAfNuQg") shouldBe "default"
        resourceUsageRuleHelper.getRuleNameByEntityId("counterparty-VLDLAz68D7DVTi6kzrKnaB") shouldBe "default"
        resourceUsageRuleHelper.getRuleNameByEntityId("counterparty-GScBzgJ2e81HH9apA1SdCi3gk6MgTXLpUnQAMYMtB2qY") shouldBe "default"
        resourceUsageRuleHelper.getRuleNameByEntityId("randomToken") shouldBe "default"
        resourceUsageRuleHelper.getRuleNameByEntityId("128.0.0.1") shouldBe "custom"
      }
    }
  }

  "ResourceUsageRuleConfig" - {
    val whiteListedToken = Set("127.0.0.1/16", "192.0.23.14/24", "valid_whitelisted_token", "199.0.0.1")
    val blackListedToken = Set("198.0.0.1/32", "15.0.12.11/12", "valid_blacklisted_token", "200.0.0.2")
    val whiteListedIP = "127.0.0.1"
    val blackListedIP = "198.0.0.1"
    val randomIPAddress = "188.0.0.1"

    val resourceUsageRuleConfig = ResourceUsageRuleConfig(applyUsageRules = true, persistAllBucketUsages=false,
      snapshotAfterEvents = 0, Map.empty[String, UsageRule], Map.empty[String, Set[String]],
      blackListedToken, whiteListedToken, Map.empty[String, ViolationActions])

    "when tested ip address for whitelisted or blacklisted check" - {
      "should respond accordingly" in {
        resourceUsageRuleConfig.isWhitelisted(whiteListedIP, randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isWhitelisted(randomIPAddress, "127.0.0.2") shouldBe true
        resourceUsageRuleConfig.isWhitelisted(randomIPAddress, "199.0.0.1") shouldBe true
        resourceUsageRuleConfig.isWhitelisted(randomIPAddress, "127.0.255.255") shouldBe true
        resourceUsageRuleConfig.isWhitelisted(randomIPAddress, "192.0.23.255") shouldBe true
        resourceUsageRuleConfig.isWhitelisted(randomIPAddress, "valid_whitelisted_token") shouldBe true
        resourceUsageRuleConfig.isBlacklisted(randomIPAddress, "198.0.0.1") shouldBe true
        resourceUsageRuleConfig.isBlacklisted(randomIPAddress, "200.0.0.2") shouldBe true
        resourceUsageRuleConfig.isBlacklisted(randomIPAddress, "15.15.255.255") shouldBe true
      }
    }

    "when provided random ip and random token" - {
      "should return false" in {
        resourceUsageRuleConfig.isWhitelisted(randomIPAddress, "10.0.1.1") shouldBe false
        resourceUsageRuleConfig.isWhitelisted(randomIPAddress, "valid_whitelisted_to") shouldBe false
        resourceUsageRuleConfig.isBlacklisted(randomIPAddress, "19.0.1.1") shouldBe false
      }
    }

    "when provided any out of range ip (ipv4 ip)" - {
      "should return false" in {
        resourceUsageRuleConfig.isWhitelisted(randomIPAddress, "11.0.23.255") shouldBe false
        resourceUsageRuleConfig.isBlacklisted(randomIPAddress, "10.15.255.255") shouldBe false
      }
    }

    "when provided blacklisted/whitelisted token and any other IP" - {
      "should return true" in {
        resourceUsageRuleConfig.isBlacklisted(randomIPAddress, "valid_blacklisted_token") shouldBe true
        resourceUsageRuleConfig.isBlacklisted(randomIPAddress, "valid_blacklisted_token") shouldBe true
        resourceUsageRuleConfig.isWhitelisted(randomIPAddress, "valid_whitelisted_token") shouldBe true
        resourceUsageRuleConfig.isWhitelisted(randomIPAddress, "valid_whitelisted_token") shouldBe true
      }
    }

    "when provided randomToken token and whitelisted/blacklisted IP" - {
      "should return true" in {
        resourceUsageRuleConfig.isBlacklisted("random_token",blackListedIP) shouldBe true
        resourceUsageRuleConfig.isWhitelisted("random_token",whiteListedIP) shouldBe true
      }
    }
  }

  def getExpectedResourceUsageRule: ResourceUsageRuleConfig = {
    val endpointDefaultUsageRule = ResourceUsageRule(
      Map (
        300 -> BucketRule(100, "50"),
        600 -> BucketRule(200, "70"),
        1200 -> BucketRule(400, "90")
    ))

    val postAgencyMsgUsageRule = ResourceUsageRule(
      Map (
        300 -> BucketRule(100, "50"),
        600 -> BucketRule(200, "70"),
        1200 -> BucketRule(400, "90")
    ))

    val endpointAllUsageRule = ResourceUsageRule(
      Map (
        300 -> BucketRule(1000, "50"),
        600 -> BucketRule(2000, "70"),
        1200 -> BucketRule(4000, "90")
      ))

    val defaultEndpointUsageRule = ResourceTypeUsageRule(
      Map (
        "default" -> endpointDefaultUsageRule,
        "POST_agency_msg" -> postAgencyMsgUsageRule,
        "endpoint.all" -> endpointAllUsageRule
    ))

    val messageDefaultUsageRule = ResourceUsageRule(
      Map (
        300 -> BucketRule(100, "50"),
        600 -> BucketRule(200, "70"),
        1200 -> BucketRule(400, "90")
    ))

    val dummyMsgUsageRule = ResourceUsageRule(
      Map (
        300 -> BucketRule(3, "100"),
        600 -> BucketRule(3, "101"),
        1200 -> BucketRule(3, "102"),
        1800 -> BucketRule(4, "103"),
    ))

    val createMsgConnReqUsageRule = ResourceUsageRule(
      Map (
        -1 -> BucketRule(100, "70", persistUsageState = true),
        300 -> BucketRule(5, "50"),
        600 -> BucketRule(20, "70"),
        1800 -> BucketRule(50, "90")
      ))

    val messageAllUsageRule = ResourceUsageRule(
      Map (
        300 -> BucketRule(500, "50"),
        600 -> BucketRule(1000, "70"),
        1200 -> BucketRule(2000, "90")
      ))

    val customCreateMsgConnReqUsageRule = ResourceUsageRule(
      Map (
        -1 -> BucketRule(2, "70", persistUsageState = true),
        300 -> BucketRule(5, "50"),
        600 -> BucketRule(20, "70"),
        1800 -> BucketRule(50, "90")
    ))

    val defaultMessageUsageRule = ResourceTypeUsageRule( Map (
      "default" ->  messageDefaultUsageRule,
      "dummy-family/DUMMY_MSG" -> dummyMsgUsageRule,
      "connecting/CREATE_MSG_connReq" -> createMsgConnReqUsageRule,
      "message.all" -> messageAllUsageRule
    ))

    val customMessageUsageRule = ResourceTypeUsageRule( Map (
      "default" ->  messageDefaultUsageRule,
      "connecting/CREATE_MSG_connReq" -> customCreateMsgConnReqUsageRule,
      "message.all" -> messageAllUsageRule
    ))

    val defaultUsageRule = UsageRule(Map(
      "endpoint" -> defaultEndpointUsageRule,
      "message" -> defaultMessageUsageRule
    ))

    val customUsageRule = UsageRule(Map(
      "endpoint" -> defaultEndpointUsageRule,
      "message" -> customMessageUsageRule
    ))

    val usageRules = Map (
      "default" -> defaultUsageRule,
      "custom" -> customUsageRule
    )

    val ruleToTokens = Map (
      "default"-> Set.empty[String],
      "custom" -> Set("127.0.2.0/24", "127.1.0.1", "128.0.0.1")
    )

    val logAndWarnMsgActions = ViolationActions ( Map (
      "log-msg" -> InstructionDetail(Map("level" -> "info")),
      "warn-resource" -> InstructionDetail(Map("entity-types" -> "ip", "period" -> 600))
    ))

    val logAndWarnIpAndBlockResourceActions = ViolationActions( Map (
      "log-msg" -> InstructionDetail( Map("level" -> "info")),
      "warn-entity" -> InstructionDetail(Map("entity-types" -> "ip", "period" -> -1)),
      "block-resource" -> InstructionDetail(Map("entity-types" -> "ip", "period" -> 600))
    ))

    val logAndBlockUserActions = ViolationActions( Map (
      "log-msg" -> InstructionDetail(Map("level" -> "info")),
      "block-entity" -> InstructionDetail(Map("entity-types" -> "ip", "period" -> -1))
    ))

    val logWarnActions = ViolationActions( Map (
      "log-msg" -> InstructionDetail(Map("level" -> "warn"))
    ))

    val logInfoBlockIp60 = ViolationActions( Map (
      "log-msg" -> InstructionDetail(Map("entity-types" -> "ip", "level" -> "info")),
      "block-resource" -> InstructionDetail(Map("entity-types" -> "ip", "period" -> 60))
    ))

    val logDebugBlockUser120 = ViolationActions( Map (
      "log-msg" -> InstructionDetail(Map("entity-types" -> "user", "level" -> "debug")),
      "block-resource" -> InstructionDetail(Map("entity-types" -> "user", "period" -> 120))
    ))

    val logTraceBlockGlobal180 = ViolationActions( Map (
      "log-msg" -> InstructionDetail(Map("entity-types" -> "global", "level" -> "trace")),
      "block-resource" -> InstructionDetail(Map("entity-types" -> "global", "period" -> 180))
    ))

    val actionRules = Map (
      "50" -> logAndWarnMsgActions,
      "70" -> logAndWarnIpAndBlockResourceActions,
      "90" -> logAndBlockUserActions,
      "100" -> logWarnActions,
      "101" -> logInfoBlockIp60,
      "102" -> logDebugBlockUser120,
      "103" -> logTraceBlockGlobal180
    )

    ResourceUsageRuleConfig(applyUsageRules = true, persistAllBucketUsages = false,
      snapshotAfterEvents = 2, usageRules, ruleToTokens, Set.empty, Set.empty, actionRules)
  }

}
