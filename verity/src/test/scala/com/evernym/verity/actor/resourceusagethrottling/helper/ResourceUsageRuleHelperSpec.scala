package com.evernym.verity.actor.resourceusagethrottling.helper

import com.evernym.verity.testkit.BasicSpec


class ResourceUsageRuleHelperSpec extends BasicSpec {

  def getExpectedResourceUsageRule: ResourceUsageRuleConfig = {
    val defaultEndpointUsageBuckets = ResourceUsageRule( Map (
      300 -> BucketRule(100, "50"),
      600 -> BucketRule(200, "70"),
      1200 -> BucketRule(400, "90")
    ))

    val postAgencyMsgEndpointUsageBuckets = ResourceUsageRule( Map (
      300 -> BucketRule(100, "50"),
      600 -> BucketRule(200, "70"),
      1200 -> BucketRule(400, "90")
    ))

    val defaultEndpointUsageItemBuckets = ResourceTypeUsageRule( Map (
      "default" -> defaultEndpointUsageBuckets,
      "POST_agency_msg" -> postAgencyMsgEndpointUsageBuckets
    ))

    val defaultMsgUsageBuckets = ResourceUsageRule( Map (
      300 -> BucketRule(100, "50"),
      600 -> BucketRule(200, "70"),
      1200 -> BucketRule(400, "90")
    ))

    val connReqMessageBuckets = ResourceUsageRule( Map (
      -1 -> BucketRule(100, "70", persistUsageState = true),
      300 -> BucketRule(5, "50"),
      600 -> BucketRule(20, "70"),
      1800 -> BucketRule(50, "90")
    ))

    val getMsgsMessageBuckets = ResourceUsageRule( Map (
      300 -> BucketRule(3, "100"),
      600 -> BucketRule(3, "101"),
      1200 -> BucketRule(3, "102"),
      1800 -> BucketRule(4, "103"),
    ))

    val customConnReqMessageBuckets = ResourceUsageRule( Map (
      -1 -> BucketRule(2, "70", persistUsageState = true),
      300 -> BucketRule(5, "50"),
      600 -> BucketRule(20, "70"),
      1800 -> BucketRule(50, "90")
    ))

    val customGetMsgsMessageBuckets = ResourceUsageRule( Map (
      600 -> BucketRule(200, "100"),
    ))

    val defaultMessageUsageItemBuckets = ResourceTypeUsageRule( Map (
      "CREATE_MSG_connReq" -> connReqMessageBuckets,
      "DUMMY_MSG" -> getMsgsMessageBuckets,
      "default" ->  defaultMsgUsageBuckets
    ))

    val customMessageUsageItemBuckets = ResourceTypeUsageRule( Map (
      "CREATE_MSG_connReq" -> customConnReqMessageBuckets,
      "default" ->  defaultMsgUsageBuckets
    ))

    val defaultUsageRule = UsageRule(Map(
      "endpoint" -> defaultEndpointUsageItemBuckets,
      "message" -> defaultMessageUsageItemBuckets
    ))

    val customUsageRule = UsageRule(Map(
      "endpoint" -> defaultEndpointUsageItemBuckets,
      "message" -> customMessageUsageItemBuckets
    ))

    val expectedRules = Map (
      "default" -> defaultUsageRule,
      "custom" -> customUsageRule
    )

    val ruleToTokens = Map (
      "default"-> Set.empty[String],
      "custom" -> Set("127.0.2.0/24", "127.1.0.1", "randomToken", "128.0.0.1")
    )

    val logAndWarnMsgActions = ViolationActions ( Map (
      "log-msg" -> InstructionDetail(Map("level" -> "info")),
      "warn-resource" -> InstructionDetail(Map("track-by" -> "ip", "period" -> 600))
    ))

    val logAndWarnIpAndBlockResourceActions = ViolationActions( Map (
      "log-msg" -> InstructionDetail( Map("level" -> "info")),
      "warn-user" -> InstructionDetail(Map("track-by" -> "ip", "period" -> -1)),
      "block-resource" -> InstructionDetail(Map("track-by" -> "ip", "period" -> 600))
    ))

    val logAndBlockUserActions = ViolationActions( Map (
      "log-msg" -> InstructionDetail(Map("level" -> "info")),
      "block-user" -> InstructionDetail(Map("track-by" -> "ip", "period" -> -1))
    ))

    val logWarnActions = ViolationActions( Map (
      "log-msg" -> InstructionDetail(Map("level" -> "warn"))
    ))

    val logInfoBlockIp60 = ViolationActions( Map (
      "log-msg" -> InstructionDetail(Map("track-by" -> "ip", "level" -> "info")),
      "block-resource" -> InstructionDetail(Map("track-by" -> "ip", "period" -> 60))
    ))

    val logDebugBlockUser120 = ViolationActions( Map (
      "log-msg" -> InstructionDetail(Map("track-by" -> "user", "level" -> "debug")),
      "block-resource" -> InstructionDetail(Map("track-by" -> "user", "period" -> 120))
    ))

    val logTraceBlockGlobal180 = ViolationActions( Map (
      "log-msg" -> InstructionDetail(Map("track-by" -> "global", "level" -> "trace")),
      "block-resource" -> InstructionDetail(Map("track-by" -> "global", "period" -> 180))
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
      snapshotAfterEvents = 2, expectedRules, ruleToTokens, Set.empty, Set.empty, actionRules)
  }

  "ResourceUsageTracker" - {

    "when initialized" - {
      "api usage rules should be loaded" in {
        ResourceUsageRuleHelper.loadResourceUsageRules()
        ResourceUsageRuleHelper.resourceUsageRules shouldBe getExpectedResourceUsageRule
      }
    }

    "when called get resource usage rule for a token" - {
      "should respond with applicable resource usage rule name" in{
        ResourceUsageRuleHelper.getRuleNameByToken("127.0.0.4") shouldBe "default"
        ResourceUsageRuleHelper.getRuleNameByToken("128.0.0.1") shouldBe "custom"
        ResourceUsageRuleHelper.getRuleNameByToken("191.0.0.4") shouldBe "default"
        ResourceUsageRuleHelper.getRuleNameByToken("randomToken") shouldBe "custom"
        ResourceUsageRuleHelper.getRuleNameByToken("otherToken") shouldBe "default"
        ResourceUsageRuleHelper.getRuleNameByToken("191.0.0.4otherToken") shouldBe "default"
        ResourceUsageRuleHelper.getRuleNameByToken("191.0.0.4/otherToken") shouldBe "default"
      }
    }
  }

  "ResourceUsageRuleConfig" - {
    val whiteListedToken = Set("127.0.0.1/16", "192.0.23.14/24", "valid_whitelisted_token", "199.0.0.1")
    val blackListedToken = Set("198.0.0.1/32", "15.0.12.11/12", "valid_blacklisted_token", "200.0.0.2")
    val randomIPAddress = "188.0.0.1"
    val whiteListedIP = "127.0.0.1"
    val blackListedIP = "198.0.0.1"

    val resourceUsageRuleConfig = ResourceUsageRuleConfig(applyUsageRules = true, persistAllBucketUsages=false,
      snapshotAfterEvents = 0, Map.empty[String, UsageRule], Map.empty[String, Set[String]],
      blackListedToken, whiteListedToken, Map.empty[String, ViolationActions])

    "when tested ip address for whitelisted or blacklisted check" - {
      "should respond accordingly" in {
        resourceUsageRuleConfig.isWhitelisted(whiteListedIP, randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isWhitelisted("127.0.0.2", randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isWhitelisted("199.0.0.1", randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isWhitelisted("127.0.255.255", randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isWhitelisted("192.0.23.255", randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isWhitelisted("valid_whitelisted_token", randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isBlacklisted("198.0.0.1", randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isBlacklisted("200.0.0.2", randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isBlacklisted("15.15.255.255", randomIPAddress) shouldBe true
      }
    }

    "when provided random ip and random token" - {
      "should return false" in {
        resourceUsageRuleConfig.isWhitelisted("10.0.1.1", randomIPAddress) shouldBe false
        resourceUsageRuleConfig.isWhitelisted("valid_whitelisted_to", randomIPAddress) shouldBe false
        resourceUsageRuleConfig.isBlacklisted("19.0.1.1", randomIPAddress) shouldBe false
      }
    }

    "when provided any out of range ip (ipv4 ip)" - {
      "should return false" in {
        resourceUsageRuleConfig.isWhitelisted("11.0.23.255", randomIPAddress) shouldBe false
        resourceUsageRuleConfig.isBlacklisted("10.15.255.255", randomIPAddress) shouldBe false
      }
    }

    "when provided blacklisted/whitelisted token and any other IP" - {
      "should return true" in {
        resourceUsageRuleConfig.isBlacklisted("valid_blacklisted_token", randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isBlacklisted("valid_blacklisted_token", whiteListedIP) shouldBe true
        resourceUsageRuleConfig.isWhitelisted("valid_whitelisted_token", randomIPAddress) shouldBe true
        resourceUsageRuleConfig.isWhitelisted("valid_whitelisted_token", blackListedIP) shouldBe true
      }
    }

    "when provided randomToken token and whitelisted/blacklisted IP" - {
      "should return true" in {
        resourceUsageRuleConfig.isBlacklisted("random_token", blackListedIP) shouldBe true
        resourceUsageRuleConfig.isWhitelisted("random_token", whiteListedIP) shouldBe true
      }
    }
  }

}
