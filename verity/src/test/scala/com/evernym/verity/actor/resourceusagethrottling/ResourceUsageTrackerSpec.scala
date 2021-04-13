package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.actor.resourceusagethrottling.tracking._

class ResourceUsageTrackerSpec
  extends BaseResourceUsageTrackerSpec
    with ResourceUsageCommon {

  "ResourceUsageTracker" - {

    "when asked to track endpoint usages" - {
      "should be tracked successfully" in {
        addUserResourceUsage(
          RESOURCE_TYPE_ENDPOINT,
          "POST_agency_msg",
          Option("1.2.3.4"),
          None
        )
        checkUsage(
          ENTITY_ID_GLOBAL,
          ResourceUsages(
            Map (
              "POST_agency_msg"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                )
            )
          )
        )
        checkUsage(
          "1.2.3.4",
          ResourceUsages(
            Map (
              "POST_agency_msg"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                )
            )
          )
        )
      }
    }

    "when asked to track endpoint usages again" - {
      "should be tracked successfully with new values" in {
        addUserResourceUsage(
          RESOURCE_TYPE_ENDPOINT,
          "POST_agency_msg",
          Option("1.2.3.4"),
          None
        )
        checkUsage(
          ENTITY_ID_GLOBAL,
          ResourceUsages(
            Map (
              "POST_agency_msg"->
                Map(
                  "300" -> BucketExt(2, 100, None, None),
                  "600" -> BucketExt(2, 200, None, None),
                  "1200" -> BucketExt(2, 400, None, None)
                )
            )
          )
        )
        checkUsage(
          "1.2.3.4",
          ResourceUsages(
            Map (
              "POST_agency_msg"->
                Map(
                  "300" -> BucketExt(2, 100, None, None),
                  "600" -> BucketExt(2, 200, None, None),
                  "1200" -> BucketExt(2, 400, None, None)
                )
            )
          )
        )
      }
    }

    "when asked to track message usages" - {
      "should be tracked successfully" in {
        addUserResourceUsage(
          RESOURCE_TYPE_MESSAGE,
          "request",
          Option("1.2.3.4"),
          Option("owner-1234")
        )
        checkUsage(
          ENTITY_ID_GLOBAL,
          ResourceUsages(
            Map (
              "request"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                )
            )
          )
        )
        checkUsage(
          "1.2.3.4",
          ResourceUsages(
            Map (
              "request"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                )
            )
          )
        )
        checkUsage(
          "owner-1234",
          ResourceUsages(
            Map (
              "request"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                )
            )
          )
        )
      }
    }

    "when asked to track another message usages" - {
      "should be tracked successfully" in {
        addUserResourceUsage(
          RESOURCE_TYPE_MESSAGE,
          "response",
          Option("1.2.3.4"),
          Option("owner-1234")
        )
        checkUsage(
          ENTITY_ID_GLOBAL,
          ResourceUsages(
            Map (
              "request"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                ),
              "response"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                )
            )
          )
        )
        checkUsage(
          "1.2.3.4",
          ResourceUsages(
            Map (
              "request"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                ),
              "response"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                )
            )
          )
        )
        checkUsage(
          "owner-1234",
          ResourceUsages(
            Map (
              "request"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                ),
              "response"->
                Map(
                  "300" -> BucketExt(1, 100, None, None),
                  "600" -> BucketExt(1, 200, None, None),
                  "1200" -> BucketExt(1, 400, None, None)
                )
            )
          )
        )
      }
    }
  }

  def checkUsage(entityId: EntityId,
                 expectedUsages: ResourceUsages): Unit = {
    sendToResourceUsageTrackerRegion(entityId, GetAllResourceUsages)
    val actualUsages = expectMsgType[ResourceUsages]
    expectedUsages.usages.foreach { expResourceUsage =>
      val actualResourceUsageOpt = actualUsages.usages.get(expResourceUsage._1)
      actualResourceUsageOpt.isDefined shouldBe true
      val actualResourceUsage = actualResourceUsageOpt.get
      expResourceUsage._2.foreach { case (expBucketId, expBucketExt) =>
        val actualBucketExtOpt = actualResourceUsage.get(expBucketId)
        actualBucketExtOpt.isDefined shouldBe true
        val actualBucketExt = actualBucketExtOpt.get
        actualBucketExt.usedCount shouldBe expBucketExt.usedCount
        actualBucketExt.allowedCount shouldBe expBucketExt.allowedCount
        actualBucketExt.startDateTime.isDefined shouldBe true
        actualBucketExt.endDateTime.isDefined shouldBe true
      }
    }
  }

}
