package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.{BlockingDetail, EntityBlockingStatus, UsageBlockingStatusChunk}
import com.evernym.verity.actor.node_singleton.ResourceBlockingStatusMngrCache
import com.evernym.verity.actor.resourceusagethrottling.helper.{ResourceUsageRuleConfig, ResourceUsageRuleHelper, UsageRule, ViolationActions}
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.testkit.BasicSpec

import java.time.ZonedDateTime


class CheckUsageBlockSpec
  extends BasicSpec {

  val ipAddressV4 = "1.2.3.4"
  val userId = "userId1"

  "ResourceUsageTracker" - {

    "for unblocked ip address and user id" - {
      "when called 'checkIfUsageIsBlocked'" - {
        "should be successful" in {
          checkResourceUsageIsAllowed(ipAddressV4, ipAddressV4, "GET_MSGS")
          checkResourceUsageIsAllowed(ipAddressV4, userId, "GET_MSGS")
        }
      }
    }

    "for blocked ip address entity" - {
      "when called 'checkIfUsageIsBlocked' for ip address entity id" - {
        "should throw exception" in {
          blockEntity(ipAddressV4)
          checkUsageBlockedExceptionIsThrown(ipAddressV4, ipAddressV4, "GET_MSGS")
        }
      }
    }

    "for blocked ip address entity" - {
      "when called 'checkIfUsageIsBlocked' for userid entity id" - {
        "should be successful" in {
          //NOTE: in real scenario, this situation won't be reached
          // as it first checks with 'ipAddress' as entity id and it will throw an exception there itself
          blockEntity(ipAddressV4)
          checkResourceUsageIsAllowed(ipAddressV4, userId, "GET_MSGS")
        }
      }
    }

    "for blocked user entity id" - {
      "when called 'checkIfUsageIsBlocked' for ip address entity id" - {
        "should be successful" in {
          blockEntity(userId)
          checkResourceUsageIsAllowed(ipAddressV4, ipAddressV4, "GET_MSGS")
        }
      }
    }

    "for blocked user entity id" - {
      "when called 'checkIfUsageIsBlocked' for userid entity id" - {
        "should throw exception" in {
          blockEntity(userId)
          checkUsageBlockedExceptionIsThrown(ipAddressV4, userId, "GET_MSGS")
        }
      }
    }

    "for blacklisted ip address" - {
      "when called 'checkIfUsageIsBlocked'" - {
        "should throw exception" in {
          val resourceUsageRuleConfig = buildResourceUsageRuleConfig(blackListedEntityIds = Set(ipAddressV4))
          checkUsageBlockedExceptionIsThrown(ipAddressV4, ipAddressV4, "GET_MSGS", resourceUsageRuleConfig)
          checkUsageBlockedExceptionIsThrown(ipAddressV4, userId, "GET_MSGS", resourceUsageRuleConfig)
        }
      }
    }

    "for blocked and whitelisted ip address" - {
      "when called 'checkIfUsageIsBlocked'" - {
        "should be successful" in {
          blockEntity(ipAddressV4)
          val resourceUsageRuleConfig = buildResourceUsageRuleConfig(whitelistedEntityIds = Set(ipAddressV4))
          checkResourceUsageIsAllowed(ipAddressV4, ipAddressV4, "GET_MSGS", resourceUsageRuleConfig)
          checkResourceUsageIsAllowed(ipAddressV4, userId, "GET_MSGS", resourceUsageRuleConfig)
        }
      }
    }

    "for blocked and whitelisted userid" - {
      "when called 'checkIfUsageIsBlocked'" - {
        "should respond accordingly" in {
          blockEntity(userId)
          val resourceUsageRuleConfig = buildResourceUsageRuleConfig(whitelistedEntityIds = Set(userId))
          checkResourceUsageIsAllowed(ipAddressV4, ipAddressV4, "GET_MSGS", resourceUsageRuleConfig)
          checkResourceUsageIsAllowed(ipAddressV4, userId, "GET_MSGS", resourceUsageRuleConfig)
        }
      }
    }

    "for blocked userid and whitelisted ip address" - {
      "when called 'checkIfUsageIsBlocked'" - {
        "should be successful" in {
          blockEntity(userId)
          val resourceUsageRuleConfig = buildResourceUsageRuleConfig(whitelistedEntityIds = Set(ipAddressV4))
          checkResourceUsageIsAllowed(ipAddressV4, ipAddressV4, "GET_MSGS", resourceUsageRuleConfig)
          checkResourceUsageIsAllowed(ipAddressV4, userId, "GET_MSGS", resourceUsageRuleConfig)
        }
      }
    }

    "for blocked ipaddress and whitelisted userid" - {
      "when called 'checkIfUsageIsBlocked'" - {
        "should respond accordingly" in {
          blockEntity(ipAddressV4)

          val resourceUsageRuleConfig = buildResourceUsageRuleConfig(whitelistedEntityIds = Set(userId))
          checkUsageBlockedExceptionIsThrown(ipAddressV4, ipAddressV4, "GET_MSGS", resourceUsageRuleConfig)

          //whitelist rule takes preference (as of now)
          checkResourceUsageIsAllowed(ipAddressV4, userId, "GET_MSGS", resourceUsageRuleConfig)
          checkResourceUsageIsAllowed("2.3.4.5", userId, "GET_MSGS", resourceUsageRuleConfig)
        }
      }
    }
  }

  def buildResourceUsageRuleConfig(blackListedEntityIds: Set[EntityId] = Set.empty,
                                   whitelistedEntityIds: Set[EntityId] = Set.empty): ResourceUsageRuleConfig = {
    ResourceUsageRuleConfig(applyUsageRules = true, persistAllBucketUsages=false,
      snapshotAfterEvents = 0, Map.empty[String, UsageRule], Map.empty[String, Set[String]],
      blackListedEntityIds, whitelistedEntityIds, Map.empty[String, ViolationActions])
  }


  def blockEntity(entityId: EntityId, sinceMinutesInPast: Int = 5): Unit = {
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

  def checkUsageBlockedExceptionIsThrown(ipAddress: IpAddress,
                                         entityId: EntityId,
                                         resourceName: ResourceName,
                                         resourceUsageRuleConfig: ResourceUsageRuleConfig = ResourceUsageRuleHelper.resourceUsageRules): Unit = {
    val ex = intercept[BadRequestErrorException] {
      ResourceUsageTracker.checkIfUsageIsBlocked(ipAddress, entityId, resourceName, resourceUsageRuleConfig)
    }
    ex.getMessage shouldBe "usage blocked"
  }

  def checkResourceUsageIsAllowed(ipAddress: IpAddress,
                                  entityId: EntityId,
                                  resourceName: ResourceName,
                                  resourceUsageRuleConfig: ResourceUsageRuleConfig = ResourceUsageRuleHelper.resourceUsageRules): Unit = {
    ResourceUsageTracker.checkIfUsageIsBlocked(ipAddress, entityId, resourceName, resourceUsageRuleConfig)
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
