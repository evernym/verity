package com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking

import com.evernym.verity.actor.cluster_singleton.ForResourceBlockingStatusMngr
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor.{CallerBlocked, CallerResourceBlocked, CallerResourceUnblocked, CallerUnblocked}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.ActorErrorResp
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.BAD_REQUEST

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class ResourceBlockingStatusMngrSpec
  extends PersistentActorSpec
    with BasicSpec {

  import com.evernym.verity.util.TimeZoneUtil.{getCurrentUTCZonedDateTime => curDate}

  implicit class RichBlockingDetail(blockingDetail: BlockingDetail) {

    def isBlockedPermanently(cdt: ZonedDateTime): Boolean =
      blockingDetail.isBlocked(cdt) &&
        blockingDetail.blockFrom.nonEmpty &&
        blockingDetail.blockTill.isEmpty

    def isBlockedTemporarily(cdt: ZonedDateTime, allDuration: Long): Boolean =
      blockingDetail.isBlocked(cdt) &&
        blockingDetail.blockFrom.nonEmpty &&
        blockingDetail.blockTill.nonEmpty &&
        ChronoUnit.SECONDS.between(blockingDetail.blockFrom.get, blockingDetail.blockTill.get) == allDuration

    def isUnblockedPermanently(cdt: ZonedDateTime): Boolean =
      blockingDetail.isUnblocked(cdt) &&
        blockingDetail.unblockFrom.nonEmpty &&
        blockingDetail.unblockTill.isEmpty

    def isUnblockedTemporarily(cdt: ZonedDateTime, allDuration: Long): Boolean =
      blockingDetail.isUnblocked(cdt) &&
        blockingDetail.unblockFrom.nonEmpty &&
        blockingDetail.unblockTill.nonEmpty &&
        ChronoUnit.SECONDS.between(blockingDetail.unblockFrom.get, blockingDetail.unblockTill.get) == allDuration

    def isNeutralAndBlockingWasRemoved(cdt: ZonedDateTime): Boolean =
      blockingDetail.isNeutral(cdt) &&
        blockingDetail.blockFrom.nonEmpty &&
        blockingDetail.blockTill == blockingDetail.blockFrom

    def isNeutralAndUnblockingWasRemoved(cdt: ZonedDateTime): Boolean =
      blockingDetail.isNeutral(cdt) &&
        blockingDetail.unblockFrom.nonEmpty &&
        blockingDetail.unblockTill == blockingDetail.unblockFrom
  }

  val user1IpAddress = "127.1.0.1"
  val user2IpAddress = "127.2.0.2"
  val user3IpAddress = "127.3.0.3"

  "ResourceBlockingStatusMngr" - {

    "initially" - {

      "when sent GetBlockedList command" - {
        "should respond with no resources/callers ever blocked or unblocked" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = false,
              onlyUnblocked = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if bl.usageBlockingStatus.isEmpty =>
          }
        }
      }

    }

    s"for $user1IpAddress" - {

      s"when sent BlockResourceForCaller command resource1 for $user1IpAddress" - {
        s"should add blocking for resource1 for $user1IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user1IpAddress, "resource1")
          )
          expectMsgPF() {
            case urb: CallerResourceBlocked if
              urb.callerId == user1IpAddress && urb.resourceName == "resource1" && urb.blockPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = true,
              onlyUnblocked = false,
              onlyActive = true,
              inChunks = true
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user1IpAddress) &&
                bl.usageBlockingStatus(user1IpAddress).status.isNeutral(curDate) &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.size == 1 &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) =>
          }
        }
      }

      s"when sent BlockResourceForCaller command for resource2 for $user1IpAddress" - {
        s"should add blocking for resource2 for $user1IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user1IpAddress, "resource2", blockPeriod = Some(300))
          )
          expectMsgPF() {
            case urb: CallerResourceBlocked if
              urb.callerId == user1IpAddress && urb.resourceName == "resource2" && urb.blockPeriod == 300 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = true,
              onlyUnblocked = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user1IpAddress) &&
                bl.usageBlockingStatus(user1IpAddress).status.isNeutral(curDate) &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus("resource2").isBlockedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent BlockCaller command for $user1IpAddress" - {
        s"should add blocking for $user1IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockCaller(user1IpAddress)
          )
          expectMsgPF() {
            case ub: CallerBlocked if
              ub.callerId == user1IpAddress && ub.blockPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = true,
              onlyUnblocked = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user1IpAddress) &&
                bl.usageBlockingStatus(user1IpAddress).status.isBlockedPermanently(curDate) &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus("resource2").isBlockedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent BlockCaller command with blockPeriod = 0 for $user1IpAddress" - {
        s"should remove blocking for $user1IpAddress but not for resources for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockCaller(user1IpAddress, blockPeriod = Some(0))
          )
          expectMsgPF() {
            case ub: CallerBlocked if
              ub.callerId == user1IpAddress && ub.blockPeriod == 0 =>
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
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user1IpAddress) &&
                bl.usageBlockingStatus(user1IpAddress).status.isNeutralAndBlockingWasRemoved(curDate) &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus("resource2").isBlockedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent BlockResourceForCaller command with blockPeriod = 0 for resource1 for $user1IpAddress" - {
        s"should remove blocking for resource1 for $user1IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user1IpAddress, "resource1", blockPeriod = Some(0))
          )
          expectMsgPF() {
            case urb: CallerResourceBlocked if
              urb.callerId == user1IpAddress && urb.resourceName == "resource1" && urb.blockPeriod == 0 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = false,
              onlyUnblocked = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user1IpAddress) &&
                bl.usageBlockingStatus(user1IpAddress).status.isNeutralAndBlockingWasRemoved(curDate) &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus("resource1").isNeutralAndBlockingWasRemoved(curDate) &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user1IpAddress).resourcesStatus("resource2").isBlockedTemporarily(curDate, 300) =>
          }
        }
      }

    }

    s"for $user2IpAddress" - {

      s"when sent BlockResourceForCaller command for resource1 for $user2IpAddress" - {
        s"should add blocking for resource1 for $user2IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user2IpAddress, "resource1", blockFrom = Some(ZonedDateTime.now()))
          )
          expectMsgPF() {
            case urb: CallerResourceBlocked if
              urb.callerId == user2IpAddress && urb.resourceName == "resource1" && urb.blockPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = true,
              onlyUnblocked = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user2IpAddress) &&
                bl.usageBlockingStatus(user2IpAddress).status.isNeutral(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.size == 1 &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) =>
          }
        }
      }

      s"when sent BlockResourceForCaller command for resource2 for $user2IpAddress" - {
        s"should add blocking for resource2 for $user2IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user2IpAddress, "resource2", blockFrom = Some(ZonedDateTime.now()), blockPeriod = Some(300))
          )
          expectMsgPF() {
            case urb: CallerResourceBlocked if
              urb.callerId == user2IpAddress && urb.resourceName == "resource2" && urb.blockPeriod == 300 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = true,
              onlyUnblocked = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user2IpAddress) &&
                bl.usageBlockingStatus(user2IpAddress).status.isNeutral(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource2").isBlockedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent UnblockResourceForCaller command for resource2 for $user2IpAddress" - {
        s"should add unblocking and remove blocking for resource2 for $user2IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            UnblockResourceForCaller(user2IpAddress, "resource2", unblockFrom = Some(ZonedDateTime.now()))
          )
          expectMsgPF() {
            case urub: CallerResourceUnblocked if
              urub.callerId == user2IpAddress && urub.resourceName == "resource2" && urub.unblockPeriod == -1 =>
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
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user2IpAddress) &&
                bl.usageBlockingStatus(user2IpAddress).status.isNeutral(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource2").isUnblockedPermanently(curDate) =>
          }
        }
      }

      s"when sent BlockCaller command for $user2IpAddress" - {
        s"should add blocking for $user2IpAddress and remove unblocking for resource2 for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockCaller(user2IpAddress, blockPeriod = Some(600))
          )
          expectMsgPF() {
            case ub: CallerBlocked if
              ub.callerId == user2IpAddress && ub.blockPeriod == 600 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = false,
              onlyUnblocked = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user2IpAddress) &&
                bl.usageBlockingStatus(user2IpAddress).status.isBlockedTemporarily(curDate, 600) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource2").isNeutralAndUnblockingWasRemoved(curDate) =>
          }
        }
      }

      s"when sent BlockCaller command with blockPeriod = 0 and allBlockedResources = Y for $user2IpAddress" - {
        s"should remove blocking for $user2IpAddress and resource1 for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockCaller(user2IpAddress, blockPeriod = Some(0), allBlockedResources = Some("Y"))
          )
          expectMsgPF() {
            case ub: CallerBlocked if
              ub.callerId == user2IpAddress && ub.blockPeriod == 0 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = false,
              onlyUnblocked = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user2IpAddress) &&
                bl.usageBlockingStatus(user2IpAddress).status.isNeutralAndBlockingWasRemoved(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource1").isNeutralAndBlockingWasRemoved(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource2").isNeutralAndUnblockingWasRemoved(curDate) =>
          }
        }
      }

      s"when sent BlockResourceForCaller command for resource1 for $user2IpAddress again" - {
        s"should add blocking for resource1 for $user2IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user2IpAddress, "resource1")
          )
          expectMsgPF() {
            case urb: CallerResourceBlocked if
              urb.callerId == user2IpAddress && urb.resourceName == "resource1" && urb.blockPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = false,
              onlyUnblocked = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user2IpAddress) &&
                bl.usageBlockingStatus(user2IpAddress).status.isNeutralAndBlockingWasRemoved(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource2").isNeutralAndUnblockingWasRemoved(curDate) =>
          }
        }
      }

      s"when sent UnblockCaller command for $user2IpAddress" - {
        s"should add unblocking for $user2IpAddress and remove blocking for resource1 for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            UnblockCaller(user2IpAddress, unblockFrom = Some(ZonedDateTime.now()))
          )
          expectMsgPF() {
            case uub: CallerUnblocked if
              uub.callerId == user2IpAddress && uub.unblockPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = false,
              onlyUnblocked = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user2IpAddress) &&
                bl.usageBlockingStatus(user2IpAddress).status.isUnblocked(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource1").isNeutralAndBlockingWasRemoved(curDate) &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user2IpAddress).resourcesStatus("resource2").isNeutralAndUnblockingWasRemoved(curDate) =>
          }
        }
      }

      s"when sent BlockResourceForCaller command for resource1 for $user2IpAddress again" - {
        "should respond with BAD_REQUEST error status" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user2IpAddress, "resource1")
          )
          expectMsgPF() {
            case aer: ActorErrorResp if
              aer.exceptionClass == classOf[BadRequestErrorException] &&
                aer.statusCode == BAD_REQUEST.statusCode &&
                aer.statusMsg == Option("Resource cannot be blocked for entity because entity is unblocked") =>
          }
        }
      }
    }

    s"for $user3IpAddress" - {

      s"when sent BlockResourceForCaller command for resource1 for $user3IpAddress" - {
        s"should add blocking for resource1 for $user3IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user3IpAddress, "resource1")
          )
          expectMsgPF() {
            case urb: CallerResourceBlocked if
              urb.callerId == user3IpAddress && urb.resourceName == "resource1" && urb.blockPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = true,
              onlyUnblocked = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user3IpAddress) &&
                bl.usageBlockingStatus(user3IpAddress).status.isNeutral(curDate) &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.size == 1 &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) =>
          }
        }
      }

      s"when sent BlockResourceForCaller command for resource2 for $user3IpAddress" - {
        s"should add blocking for resource2 for $user3IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user3IpAddress, "resource2", blockPeriod = Some(300))
          )
          expectMsgPF() {
            case urb: CallerResourceBlocked if
              urb.callerId == user3IpAddress && urb.resourceName == "resource2" && urb.blockPeriod == 300 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = true,
              onlyUnblocked = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user3IpAddress) &&
                bl.usageBlockingStatus(user3IpAddress).status.isNeutral(curDate) &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus("resource1").isBlockedPermanently(curDate) &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus("resource2").isBlockedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent UnblockResourceForCaller command for resource1 for $user3IpAddress" - {
        s"should add unblocking and remove blocking for resource1 for $user3IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            UnblockResourceForCaller(user3IpAddress, "resource1", unblockPeriod = Some(300))
          )
          expectMsgPF() {
            case urub: CallerResourceUnblocked if
              urub.callerId == user3IpAddress && urub.resourceName == "resource1" && urub.unblockPeriod == 300 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = false,
              onlyUnblocked = true,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user3IpAddress) &&
                bl.usageBlockingStatus(user3IpAddress).status.isNeutral(curDate) &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.size == 1 && // filtered by onlyUnblocked = true
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus("resource1").isUnblockedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent BlockCaller command for $user3IpAddress" - {
        s"should add blocking for $user3IpAddress and remove unblocking for resource1 for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockCaller(user3IpAddress, blockFrom = Some(curDate))
          )
          expectMsgPF() {
            case ub: CallerBlocked if
              ub.callerId == user3IpAddress && ub.blockPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = false,
              onlyUnblocked = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user3IpAddress) &&
                bl.usageBlockingStatus(user3IpAddress).status.isBlockedPermanently(curDate) &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus("resource1").isNeutralAndUnblockingWasRemoved(curDate) &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus("resource2").isBlockedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent UnblockResourceForCaller command for resource2 for $user3IpAddress" - {
        "should respond with BAD_REQUEST error status" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            UnblockResourceForCaller(user3IpAddress, "resource2", unblockPeriod = Some(300))
          )
          expectMsgPF() {
            case aer: ActorErrorResp if
              aer.exceptionClass == classOf[BadRequestErrorException] &&
                aer.statusCode == BAD_REQUEST.statusCode &&
                aer.statusMsg == Option("Resource cannot be unblocked for entity because entity is blocked") =>
          }
        }
      }

      s"when sent UnblockCaller command for $user3IpAddress" - {
        s"should add unblocking and remove blocking for $user3IpAddress and remove blocking for resource2 for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            UnblockCaller(user3IpAddress, unblockPeriod = Some(600))
          )
          expectMsgPF() {
            case uub: CallerUnblocked if
              uub.callerId == user3IpAddress && uub.unblockPeriod == 600 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = false,
              onlyUnblocked = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user3IpAddress) &&
                bl.usageBlockingStatus(user3IpAddress).status.isUnblockedTemporarily(curDate, 600) &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus("resource1").isNeutralAndUnblockingWasRemoved(curDate) &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus("resource2").isNeutralAndBlockingWasRemoved(curDate) =>
          }
        }
      }

      s"when sent BlockResourceForCaller command with blockPeriod = 0 for resource1 for $user3IpAddress" - {
        "should respond with BAD_REQUEST error status" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockResourceForCaller(user3IpAddress, "resource1", blockPeriod = Some(0))
          )
          expectMsgPF() {
            case aer: ActorErrorResp if
              aer.exceptionClass == classOf[BadRequestErrorException] &&
                aer.statusCode == BAD_REQUEST.statusCode &&
                aer.statusMsg == Option("Resource cannot be blocked for entity because entity is unblocked") =>
          }
        }
      }

      s"when sent BlockCaller command for $user3IpAddress" - {
        s"should add blocking and remove unblocking for $user3IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            BlockCaller(user3IpAddress, blockFrom = Some(curDate), blockPeriod = Some(600))
          )
          expectMsgPF() {
            case ub: CallerBlocked if
              ub.callerId == user3IpAddress && ub.blockPeriod == 600 =>
          }
          singletonParentProxy ! ForResourceBlockingStatusMngr(
            GetBlockedList(
              onlyBlocked = false,
              onlyUnblocked = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case bl: UsageBlockingStatusChunk if
              bl.usageBlockingStatus.contains(user3IpAddress) &&
                bl.usageBlockingStatus(user3IpAddress).status.isBlockedTemporarily(curDate, 600) &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.size == 2 &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus("resource1").isNeutralAndUnblockingWasRemoved(curDate) &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus.contains("resource2") &&
                bl.usageBlockingStatus(user3IpAddress).resourcesStatus("resource2").isNeutralAndBlockingWasRemoved(curDate) =>
          }
        }
      }

    }

  }

}
