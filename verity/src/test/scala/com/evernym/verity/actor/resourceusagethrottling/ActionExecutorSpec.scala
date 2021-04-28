package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.actor.resourceusagethrottling.helper.{BucketRule, UsageViolationActionExecutor, ViolatedRule}
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec


class ActionExecutorSpec
  extends ActorSpec
    with BasicSpec {

  override def beforeAll(): Unit = {
     platform
  }

  val actionExecutor = new UsageViolationActionExecutor(system, appConfig)

  "ActionExecutor" - {

    "when asked to execute violation action 50" - {
      "should be successful" in {
        val violatedRule = buildViolationRule("50")
        actionExecutor.execute("50", violatedRule)
      }
    }

    "when asked to execute violation action 70" - {
      "should be successful" in {
        val violatedRule = buildViolationRule("70")
        actionExecutor.execute("70", violatedRule)
      }
    }

    "when asked to execute violation action 90" - {
      "should be successful" in {
        val violatedRule = buildViolationRule("90")
        actionExecutor.execute("90", violatedRule)
      }
    }

    "when asked to execute violation action 100" - {
      "should be successful" in {
        val violatedRule = buildViolationRule("100")
        actionExecutor.execute("100", violatedRule)
      }
    }

    "when asked to execute violation action 101" - {
      "should be successful" in {
        val violatedRule = buildViolationRule("101")
        actionExecutor.execute("101", violatedRule)
      }
    }

    "when asked to execute violation action 102" - {
      "should be successful" in {
        val violatedRule = buildViolationRule("102")
        actionExecutor.execute("102", violatedRule)
      }
    }

    "when asked to execute violation action 103" - {
      "should be successful" in {
        val violatedRule = buildViolationRule("103")
        actionExecutor.execute("103", violatedRule)
      }
    }

  }

  def buildViolationRule(violationActionId: String): ViolatedRule = {
    val ipAddressEntityId1 = "1.2.3.4"
    val resourceNameGetMsgs = "GET_MSGS"
    val bucketRule = BucketRule(10, violationActionId)
    val rulePath = ResourceUsageTracker.violationRulePath(
      ipAddressEntityId1, RESOURCE_TYPE_MESSAGE, resourceNameGetMsgs, None)
    ViolatedRule(ipAddressEntityId1, resourceNameGetMsgs, rulePath, 300, bucketRule, 5)
  }
}
