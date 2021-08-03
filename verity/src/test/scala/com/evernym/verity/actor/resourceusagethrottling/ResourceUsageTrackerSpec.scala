package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.resourceusagethrottling.tracking._

class ResourceUsageTrackerSpec
  extends BaseResourceUsageTrackerSpec
    with ResourceUsageCommon {

  val ipAddress = "5.6.7.8"

  "ResourceUsageTracker" - {

    "when asked to track endpoint usages" - {
      "should be tracked successfully" in {
        addUserResourceUsage(
          RESOURCE_TYPE_ENDPOINT,
          "POST_agency_msg",
          ipAddress,
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
          ipAddress,
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
          ipAddress,
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
          ipAddress,
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
          ipAddress,
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
          ipAddress,
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
          ipAddress,
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
          ipAddress,
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

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp

}
