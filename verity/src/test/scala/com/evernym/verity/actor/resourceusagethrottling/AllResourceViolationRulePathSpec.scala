package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageRuleHelper
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigValueFactory}
import org.scalatest.BeforeAndAfterAll

import scala.collection.JavaConverters._

class AllResourceViolationRulePathSpec extends BasicSpec with BeforeAndAfterAll {

  val entityIdForDefaultUsageRule = "127.4.0.4"

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

  "AllResourceViolationRulePath" - {

    "violation rule path for 'endpoint.all' resource usage rule" - {
      "should contain single 'endpoint' key" in {
        ResourceUsageTracker.violationRulePath(
          entityIdForDefaultUsageRule,
          RESOURCE_TYPE_ENDPOINT,
          "endpoint.all",
          None
        ) shouldBe "verity.resource-usage-rules.usage-rules.default.endpoint.all"
      }
    }

    "violation rule path for any other endpoint resource usage rule" - {
      "should contain single 'endpoint' key" in {
        ResourceUsageTracker.violationRulePath(
          entityIdForDefaultUsageRule,
          RESOURCE_TYPE_ENDPOINT,
          "POST_agency_msg",
          None
        ) shouldBe "verity.resource-usage-rules.usage-rules.default.endpoint.POST_agency_msg"
      }
    }

    "violation rule path for 'message.all' resource usage rule" - {
      "should contain single 'message' key" in {
        ResourceUsageTracker.violationRulePath(
          entityIdForDefaultUsageRule,
          RESOURCE_TYPE_MESSAGE,
          "message.all",
          None
        ) shouldBe "verity.resource-usage-rules.usage-rules.default.message.all"
      }
    }

    "violation rule path for any other message resource usage rule" - {
      "should contain single 'message' key" in {
        ResourceUsageTracker.violationRulePath(
          entityIdForDefaultUsageRule,
          RESOURCE_TYPE_MESSAGE,
          "CREATE_MSG_connReq",
          None
        ) shouldBe "verity.resource-usage-rules.usage-rules.default.message.CREATE_MSG_connReq"
      }
    }

  }

}
