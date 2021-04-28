package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.{BlockingDetail, EntityBlockingStatus, UsageBlockingStatusChunk}
import com.evernym.verity.actor.node_singleton.ResourceBlockingStatusMngrCache
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.testkit.BasicSpec

import java.time.ZonedDateTime

class CheckUsageBlockSpec
  extends BasicSpec {


  "ResourceUsageTracker" - {

    "for unblocked entity" - {
      "when called 'checkIfUsageIsBlocked'" - {
        "should be successful" in {
          ResourceUsageTracker.checkIfUsageIsBlocked(
            "entityId1", "GET_MSGS")
        }
      }
    }

    "for blocked entity" - {
      "when called 'checkIfUsageIsBlocked'" - {
        "should throw exception" in {
          blockEntity("entityId1", 5)
          val ex = intercept[BadRequestErrorException] {
            ResourceUsageTracker.checkIfUsageIsBlocked("entityId1", "GET_MSGS")
          }
          ex.getMessage shouldBe "usage blocked"
        }
      }
    }

    "for blocked entity resource" - {
      "when called 'checkIfUsageIsBlocked'" - {
        "should throw exception" in {
          blockEntityResource("entityId1", "GET_MSGS", 5)
          val ex = intercept[BadRequestErrorException] {
            ResourceUsageTracker.checkIfUsageIsBlocked("entityId1", "GET_MSGS")
          }
          ex.getMessage shouldBe "usage blocked"
        }
      }
    }
  }


  def blockEntity(entityId: EntityId, sinceMinutesInPast: Int): Unit = {
    val ubsc = UsageBlockingStatusChunk(
      Map(
        entityId -> createEntityBlockingStatus(sinceMinutesInPast)
      ),
      currentChunkNumber = 1,
      totalChunks = 1
    )
    ResourceBlockingStatusMngrCache.initBlockingList(ubsc)
  }

  def blockEntityResource(entityId: EntityId, resourceName: ResourceName, sinceMinutesInPast: Int): Unit = {
    val ubsc = UsageBlockingStatusChunk(
      Map(
        entityId -> createEntityResourceBlockingStatus(resourceName, sinceMinutesInPast)
      ),
      currentChunkNumber = 1,
      totalChunks = 1
    )
    ResourceBlockingStatusMngrCache.initBlockingList(ubsc)
  }

  /**
   *
   * @param sinceMinutesInPast blocking time will start from supplied minutes in past
   * @return
   */
  def createEntityBlockingStatus(sinceMinutesInPast: Int): EntityBlockingStatus = {
    EntityBlockingStatus(
      BlockingDetail(Option(ZonedDateTime.now().minusMinutes(sinceMinutesInPast)), None, None, None),
      Map.empty
    )
  }

  def createEntityResourceBlockingStatus(resourceName: ResourceName, sinceMinutesInPast: Int): EntityBlockingStatus = {
    EntityBlockingStatus(
      BlockingDetail(None, None, None, None),
      Map(
        resourceName -> BlockingDetail(Option(ZonedDateTime.now().minusMinutes(sinceMinutesInPast)), None, None, None)
      )
    )
  }
}
