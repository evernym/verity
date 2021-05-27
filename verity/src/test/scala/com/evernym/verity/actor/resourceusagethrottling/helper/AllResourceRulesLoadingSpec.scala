package com.evernym.verity.actor.resourceusagethrottling.helper

import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigValueFactory}
import org.scalatest.BeforeAndAfterAll

import scala.collection.JavaConverters._

class AllResourceRulesLoadingSpec extends BasicSpec with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    AppConfigWrapper.setConfig(customizeConfig(AppConfigWrapper.config))
    ResourceUsageRuleHelper.loadResourceUsageRules()
  }

  override protected def afterAll(): Unit = {
    try {
      AppConfigWrapper.reload()
      ResourceUsageRuleHelper.loadResourceUsageRules()
    } finally {
      super.afterAll()
    }
  }

  private def customizeConfig(config: Config): Config = {
    config
      .withValue(
        "verity.resource-usage-rules.usage-rules.default.endpoint.all",
        ConfigValueFactory.fromMap(Map(
          "600" -> Map(
            "allowed-counts" -> "150",
            "violation-action-id" -> "90",
          ).asJava,
        ).asJava))
      .withValue(
        "verity.resource-usage-rules.usage-rules.default.message.all",
        ConfigValueFactory.fromMap(Map(
          "300" -> Map(
            "allowed-counts" -> "75",
            "violation-action-id" -> "70",
          ).asJava,
        ).asJava))
  }

  "AllResourceRulesLoading" - {

    "usage rule for 'all' resource of 'endpoint' type" - {
      "should be loaded into 'endpoint.all' ResourceUsageRule" in {
        ResourceUsageRuleHelper.resourceUsageRules
          .usageRules("default")
          .resourceTypeUsageRules("endpoint")
          .resourceUsageRules("endpoint.all") should be
        ResourceUsageRule(bucketRules = Map(
          600 -> BucketRule(allowedCount = 150, violationActionId = "90"),
        ))
      }
    }

    "'endpoint' ResourceTypeUsageRule" - {
      "should not contain 'all' ResourceUsageRule" in {
        ResourceUsageRuleHelper.resourceUsageRules
          .usageRules("default")
          .resourceTypeUsageRules("endpoint")
          .resourceUsageRules should not contain key ("all")
      }
    }

    "usage rule for 'all' resource of 'message' type" - {
      "should be loaded into 'message.all' ResourceUsageRule" in {
        ResourceUsageRuleHelper.resourceUsageRules
          .usageRules("default")
          .resourceTypeUsageRules("message")
          .resourceUsageRules("message.all") should be
        ResourceUsageRule(bucketRules = Map(
          300 -> BucketRule(allowedCount = 75, violationActionId = "70"),
        ))
      }
    }

    "`message` ResourceTypeUsageRule" - {
      "should not contain 'all' ResourceUsageRule" in {
        ResourceUsageRuleHelper.resourceUsageRules
          .usageRules("default")
          .resourceTypeUsageRules("message")
          .resourceUsageRules should not contain key ("all")
      }
    }

  }

}
