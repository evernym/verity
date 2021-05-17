package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.actor.resourceusagethrottling.helper.{BucketRule, UsageViolationActionExecutor, ViolatedRule}
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.actor.testkit.{ActorSpec, CommonSpecUtil}
import com.evernym.verity.testkit.BasicSpec


class ActionExecutorSpec
  extends ActorSpec
    with BasicSpec {

  override def beforeAll(): Unit = {
     platform
  }

  val ipAddressEntityId1 = "1.2.3.4"
  val ownerUserEntityId = "owner-" + CommonSpecUtil.generateNewDid().DID
  val counterPartyUserEntityId = "counterparty-" + CommonSpecUtil.generateNewDid().verKey

  val actionExecutor = new UsageViolationActionExecutor(system, appConfig)

  "ActionExecutor" - {

    "for global entity id" - {
      "when asked to execute different violation action" - {
        "should be successful" in {
          buildAndExecuteAction(ENTITY_ID_GLOBAL, "50", 1)
          buildAndExecuteAction(ENTITY_ID_GLOBAL, "70", 1)
          buildAndExecuteAction(ENTITY_ID_GLOBAL, "90", 1)
          buildAndExecuteAction(ENTITY_ID_GLOBAL, "100", 1)
          buildAndExecuteAction(ENTITY_ID_GLOBAL, "101", 0)
          buildAndExecuteAction(ENTITY_ID_GLOBAL, "102", 0)
          buildAndExecuteAction(ENTITY_ID_GLOBAL, "103", 2)
        }
      }
    }

    "for ip-address entity id" - {
      "when asked to execute different violation action" - {
        "should be successful" in {
          buildAndExecuteAction(ipAddressEntityId1, "50", 2)
          buildAndExecuteAction(ipAddressEntityId1, "70", 3)
          buildAndExecuteAction(ipAddressEntityId1, "90", 2)
          buildAndExecuteAction(ipAddressEntityId1, "100", 1)
          buildAndExecuteAction(ipAddressEntityId1, "101", 2)
          buildAndExecuteAction(ipAddressEntityId1, "102", 0)
          buildAndExecuteAction(ipAddressEntityId1, "103", 0)
        }
      }
    }

    "for owner user entity id" - {
      "when asked to execute different violation action" - {
        "should be successful" in {
          buildAndExecuteAction(ownerUserEntityId, "50", 1)
          buildAndExecuteAction(ownerUserEntityId, "70", 1)
          buildAndExecuteAction(ownerUserEntityId, "90", 1)
          buildAndExecuteAction(ownerUserEntityId, "100", 1)
          buildAndExecuteAction(ownerUserEntityId, "101", 0)
          buildAndExecuteAction(ownerUserEntityId, "102", 2)
          buildAndExecuteAction(ownerUserEntityId, "103", 0)
        }
      }
    }

    "for counterparty user entity id" - {
      "when asked to execute different violation action" - {
        "should be successful" in {
          buildAndExecuteAction(counterPartyUserEntityId, "50", 1)
          buildAndExecuteAction(counterPartyUserEntityId, "70", 1)
          buildAndExecuteAction(counterPartyUserEntityId, "90", 1)
          buildAndExecuteAction(counterPartyUserEntityId, "100", 1)
          buildAndExecuteAction(counterPartyUserEntityId, "101", 0)
          buildAndExecuteAction(counterPartyUserEntityId, "102", 2)
          buildAndExecuteAction(counterPartyUserEntityId, "103", 0)
        }
      }
    }

  }

  def buildAndExecuteAction(entityId: EntityId,
                            violationActionId: String,
                            expectedExecutedCount: Int): Unit = {
    val violatedRule = buildViolationRule(entityId, violationActionId)
    actionExecutor.execute(violationActionId, violatedRule) shouldBe expectedExecutedCount
  }

  def buildViolationRule(entityId: EntityId, violationActionId: String): ViolatedRule = {
    val resourceNameGetMsgs = "GET_MSGS"
    val bucketRule = BucketRule(10, violationActionId)
    val rulePath = ResourceUsageTracker.violationRulePath(
      entityId, RESOURCE_TYPE_MESSAGE, resourceNameGetMsgs, None)
    ViolatedRule(entityId, resourceNameGetMsgs, rulePath, 300, bucketRule, 5)
  }
}
