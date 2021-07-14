package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.actor.{CallerBlocked, CallerResourceBlocked, CallerResourceUnblocked, CallerResourceUnwarned, CallerUnblocked, CallerUnwarned}
import com.evernym.verity.actor.cluster_singleton.{ForResourceBlockingStatusMngr, ForResourceWarningStatusMngr}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.{BlockCaller, BlockResourceForCaller, GetBlockedList, UnblockCaller, UnblockResourceForCaller, UsageBlockingStatusChunk}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.{GetWarnedList, UnwarnCaller, UnwarnResourceForCaller, UsageWarningStatusChunk}
import com.evernym.verity.actor.resourceusagethrottling.tracking.{BucketExt, ResourceUsageCommon, ResourceUsages}
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.USAGE_BLOCKED

class ResourceUsageRestrictionSpec
  extends BaseResourceUsageTrackerSpec
    with ResourceUsageCommon {

  val ipAddress = "5.6.7.8"

  "ResourceUsageTracker" - {

    "when there are no warnings, unwarnings, blockings and unblockings" - {
      "resource usage should be counted normally" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceWarningStatusMngr(
          GetWarnedList(
            onlyWarned = false,
            onlyUnwarned = false,
            onlyActive = true,
            inChunks = false
          )
        )
        expectMsgPF() {
          case wl: UsageWarningStatusChunk if wl.usageWarningStatus.isEmpty =>
        }
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          GetBlockedList(
            onlyBlocked = false,
            onlyUnblocked = false,
            onlyActive = true,
            inChunks = false
          )
        )
        expectMsgPF() {
          case bl: UsageBlockingStatusChunk if bl.usageBlockingStatus.isEmpty =>
        }

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(1, 200, None, None))))
        )
      }
    }

    "when resource blocking has been added" - {
      "resource usage should throw exception with USAGE_BLOCKED status" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          BlockResourceForCaller(ipAddress, "resource1")
        )
        expectMsgPF() {
          case urb: CallerResourceBlocked if
            urb.callerId == ipAddress && urb.resourceName == "resource1" && urb.blockPeriod == -1 =>
        }
        Thread.sleep(100)

        val e = the [BadRequestErrorException] thrownBy {
          addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        }
        e.respCode shouldBe USAGE_BLOCKED.statusCode
        e.getMessage shouldBe USAGE_BLOCKED.statusMsg
      }
    }

    "when resource blocking has been removed" - {
      "resource usage count should be reset and then resource usage should be counted normally" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          BlockResourceForCaller(ipAddress, "resource1", blockPeriod = Some(0))
        )
        expectMsgPF() {
          case urb: CallerResourceBlocked if
            urb.callerId == ipAddress && urb.resourceName == "resource1" && urb.blockPeriod == 0 =>
        }
        Thread.sleep(100)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(0, 200, None, None))))
        )

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(1, 200, None, None))))
        )
      }
    }

    "when entity blocking has been added" - {
      "resource usage should throw exception with USAGE_BLOCKED status" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          BlockCaller(ipAddress)
        )
        expectMsgPF() {
          case ub: CallerBlocked if
            ub.callerId == ipAddress && ub.blockPeriod == -1 =>
        }
        Thread.sleep(100)

        val e = the [BadRequestErrorException] thrownBy {
          addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        }
        e.respCode shouldBe USAGE_BLOCKED.statusCode
        e.getMessage shouldBe USAGE_BLOCKED.statusMsg
      }
    }

    "when entity blocking has been removed" - {
      "resource usage should be counted normally" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          BlockCaller(ipAddress, blockPeriod = Some(0))
        )
        expectMsgPF() {
          case ub: CallerBlocked if
            ub.callerId == ipAddress && ub.blockPeriod == 0 =>
        }
        Thread.sleep(100)

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(2, 200, None, None))))
        )
      }
    }

    "when resource blocking has been added again" - {
      "resource usage should throw exception with USAGE_BLOCKED status" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          BlockResourceForCaller(ipAddress, "resource1")
        )
        expectMsgPF() {
          case urb: CallerResourceBlocked if
            urb.callerId == ipAddress && urb.resourceName == "resource1" && urb.blockPeriod == -1 =>
        }
        Thread.sleep(100)

        val e = the [BadRequestErrorException] thrownBy {
          addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        }
        e.respCode shouldBe USAGE_BLOCKED.statusCode
        e.getMessage shouldBe USAGE_BLOCKED.statusMsg
      }
    }

    "when entity blocking has been added again" - {
      "resource usage should throw exception with USAGE_BLOCKED status" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          BlockCaller(ipAddress)
        )
        expectMsgPF() {
          case ub: CallerBlocked if
            ub.callerId == ipAddress && ub.blockPeriod == -1 =>
        }
        Thread.sleep(100)

        val e = the [BadRequestErrorException] thrownBy {
          addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        }
        e.respCode shouldBe USAGE_BLOCKED.statusCode
        e.getMessage shouldBe USAGE_BLOCKED.statusMsg
      }
    }

    "when entity blocking has been removed together with resources blockings" - {
      "resource usage count should be reset and then resource usage should be counted normally" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          BlockCaller(ipAddress, blockPeriod = Some(0), allBlockedResources = Some("Y"))
        )
        expectMsgPF() {
          case ub: CallerBlocked if
            ub.callerId == ipAddress && ub.blockPeriod == 0 =>
        }
        Thread.sleep(100)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(0, 200, None, None))))
        )

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(1, 200, None, None))))
        )
      }
    }

    "when resource unblocking has been added" - {
      "resource usage should not be counted" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          UnblockResourceForCaller(ipAddress, "resource1")
        )
        expectMsgPF() {
          case urub: CallerResourceUnblocked if
            urub.callerId == ipAddress && urub.resourceName == "resource1" && urub.unblockPeriod == -1 =>
        }
        Thread.sleep(100)

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(1, 200, None, None))))
        )
      }
    }

    "when resource unblocking has been removed" - {
      "resource usage should be counted normally" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          UnblockResourceForCaller(ipAddress, "resource1", unblockPeriod = Some(0))
        )
        expectMsgPF() {
          case urub: CallerResourceUnblocked if
            urub.callerId == ipAddress && urub.resourceName == "resource1" && urub.unblockPeriod == 0 =>
        }
        Thread.sleep(100)

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(2, 200, None, None))))
        )
      }
    }

    "when entity unblocking has been added" - {
      "resource usage should not be counted" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          UnblockCaller(ipAddress)
        )
        expectMsgPF() {
          case uub: CallerUnblocked if
            uub.callerId == ipAddress && uub.unblockPeriod == -1 =>
        }
        Thread.sleep(100)

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(2, 200, None, None))))
        )
      }
    }

    "when entity unblocking has been removed" - {
      "resource usage should be counted normally" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceBlockingStatusMngr(
          UnblockCaller(ipAddress, unblockPeriod = Some(0))
        )
        expectMsgPF() {
          case uub: CallerUnblocked if
            uub.callerId == ipAddress && uub.unblockPeriod == 0 =>
        }
        Thread.sleep(100)

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(3, 200, None, None))))
        )
      }
    }

    "when resource unwarning has been added" - {
      "resource usage should not be counted" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceWarningStatusMngr(
          UnwarnResourceForCaller(ipAddress, "resource1")
        )
        expectMsgPF() {
          case uruw: CallerResourceUnwarned if
            uruw.callerId == ipAddress && uruw.resourceName == "resource1" && uruw.unwarnPeriod == -1 =>
        }
        Thread.sleep(100)

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(3, 200, None, None))))
        )
      }
    }

    "when resource unwarning has been removed" - {
      "resource usage should be counted normally" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceWarningStatusMngr(
          UnwarnResourceForCaller(ipAddress, "resource1", unwarnPeriod = Some(0))
        )
        expectMsgPF() {
          case uruw: CallerResourceUnwarned if
            uruw.callerId == ipAddress && uruw.resourceName == "resource1" && uruw.unwarnPeriod == 0 =>
        }
        Thread.sleep(100)

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(4, 200, None, None))))
        )
      }
    }

    "when entity unwarning has been added" - {
      "resource usage should not be counted" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceWarningStatusMngr(
          UnwarnCaller(ipAddress)
        )
        expectMsgPF() {
          case uuw: CallerUnwarned if
            uuw.callerId == ipAddress && uuw.unwarnPeriod == -1 =>
        }
        Thread.sleep(100)

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(4, 200, None, None))))
        )
      }
    }

    "when entity unwarning has been removed" - {
      "resource usage should be counted normally" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        singletonParentProxy ! ForResourceWarningStatusMngr(
          UnwarnCaller(ipAddress, unwarnPeriod = Some(0))
        )
        expectMsgPF() {
          case uuw: CallerUnwarned if
            uuw.callerId == ipAddress && uuw.unwarnPeriod == 0 =>
        }
        Thread.sleep(100)

        addUserResourceUsage(RESOURCE_TYPE_MESSAGE, "resource1", ipAddress, None)
        checkUsage(
          ipAddress,
          ResourceUsages(Map("resource1" -> Map("600" -> BucketExt(5, 200, None, None))))
        )
      }
    }

  }

}
