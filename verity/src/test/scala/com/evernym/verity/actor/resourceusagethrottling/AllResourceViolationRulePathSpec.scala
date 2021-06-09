package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.testkit.BasicSpec

class AllResourceViolationRulePathSpec extends BasicSpec {

  val entityIdForDefaultUsageRule = "127.4.0.4"

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
          "connecting/CREATE_MSG_connReq",
          None
        ) shouldBe "verity.resource-usage-rules.usage-rules.default.message.connecting/CREATE_MSG_connReq"
      }
    }

  }

}
