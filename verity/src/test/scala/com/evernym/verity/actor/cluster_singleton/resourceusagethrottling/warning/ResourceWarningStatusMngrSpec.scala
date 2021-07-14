package com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning

import com.evernym.verity.actor.cluster_singleton.ForResourceWarningStatusMngr
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor.{CallerResourceUnwarned, CallerResourceWarned, CallerUnwarned, CallerWarned}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.ActorErrorResp
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.BAD_REQUEST

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class ResourceWarningStatusMngrSpec
  extends PersistentActorSpec
    with BasicSpec {

  import com.evernym.verity.util.TimeZoneUtil.{getCurrentUTCZonedDateTime => curDate}

  implicit class RichWarningDetail(warningDetail: WarningDetail) {

    def isWarnedPermanently(cdt: ZonedDateTime): Boolean =
      warningDetail.isWarned(cdt) &&
        warningDetail.warnFrom.nonEmpty &&
        warningDetail.warnTill.isEmpty

    def isWarnedTemporarily(cdt: ZonedDateTime, allDuration: Long): Boolean =
      warningDetail.isWarned(cdt) &&
        warningDetail.warnFrom.nonEmpty &&
        warningDetail.warnTill.nonEmpty &&
        ChronoUnit.SECONDS.between(warningDetail.warnFrom.get, warningDetail.warnTill.get) == allDuration

    def isUnwarnedPermanently(cdt: ZonedDateTime): Boolean =
      warningDetail.isUnwarned(cdt) &&
        warningDetail.unwarnFrom.nonEmpty &&
        warningDetail.unwarnTill.isEmpty

    def isUnwarnedTemporarily(cdt: ZonedDateTime, allDuration: Long): Boolean =
      warningDetail.isUnwarned(cdt) &&
        warningDetail.unwarnFrom.nonEmpty &&
        warningDetail.unwarnTill.nonEmpty &&
        ChronoUnit.SECONDS.between(warningDetail.unwarnFrom.get, warningDetail.unwarnTill.get) == allDuration

    def isNeutralAndWarningWasRemoved(cdt: ZonedDateTime): Boolean =
      warningDetail.isNeutral(cdt) &&
        warningDetail.warnFrom.nonEmpty &&
        warningDetail.warnTill == warningDetail.warnFrom

    def isNeutralAndUnwarningWasRemoved(cdt: ZonedDateTime): Boolean =
      warningDetail.isNeutral(cdt) &&
        warningDetail.unwarnFrom.nonEmpty &&
        warningDetail.unwarnTill == warningDetail.unwarnFrom
  }

  val user1IpAddress = "127.1.0.1"
  val user2IpAddress = "127.2.0.2"
  val user3IpAddress = "127.3.0.3"

  "ResourceWarningStatusMngr" - {

    "initially" - {

      "when sent GetWarnedList command initially" - {
        "should respond with no resources/callers ever warned or unwarned" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if wl.usageWarningStatus.isEmpty =>
          }
        }
      }

    }

    s"for $user1IpAddress" - {

      s"when sent WarnResourceForCaller command resource1 for $user1IpAddress" - {
        s"should add warning for resource1 for $user1IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user1IpAddress, "resource1")
          )
          expectMsgPF() {
            case urw: CallerResourceWarned if
              urw.callerId == user1IpAddress && urw.resourceName == "resource1" && urw.warnPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = true,
              onlyUnwarned = false,
              onlyActive = true,
              inChunks = true
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user1IpAddress) &&
                wl.usageWarningStatus(user1IpAddress).status.isNeutral(curDate) &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.size == 1 &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) =>
          }
        }
      }

      s"when sent WarnResourceForCaller command for resource2 for $user1IpAddress" - {
        s"should add warning for resource2 for $user1IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user1IpAddress, "resource2", warnPeriod = Some(300))
          )
          expectMsgPF() {
            case urw: CallerResourceWarned if
              urw.callerId == user1IpAddress && urw.resourceName == "resource2" && urw.warnPeriod == 300 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = true,
              onlyUnwarned = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user1IpAddress) &&
                wl.usageWarningStatus(user1IpAddress).status.isNeutral(curDate) &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus("resource2").isWarnedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent WarnCaller command for $user1IpAddress" - {
        s"should add warning for $user1IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnCaller(user1IpAddress)
          )
          expectMsgPF() {
            case uw: CallerWarned if
              uw.callerId == user1IpAddress && uw.warnPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = true,
              onlyUnwarned = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user1IpAddress) &&
                wl.usageWarningStatus(user1IpAddress).status.isWarnedPermanently(curDate) &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus("resource2").isWarnedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent WarnCaller command with warnPeriod = 0 for $user1IpAddress" - {
        s"should remove warning for $user1IpAddress but not for resources for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnCaller(user1IpAddress, warnPeriod = Some(0))
          )
          expectMsgPF() {
            case uw: CallerWarned if
              uw.callerId == user1IpAddress && uw.warnPeriod == 0 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user1IpAddress) &&
                wl.usageWarningStatus(user1IpAddress).status.isNeutralAndWarningWasRemoved(curDate) &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus("resource2").isWarnedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent WarnResourceForCaller command with warnPeriod = 0 for resource1 for $user1IpAddress" - {
        s"should remove warning for resource1 for $user1IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user1IpAddress, "resource1", warnPeriod = Some(0))
          )
          expectMsgPF() {
            case urw: CallerResourceWarned if
              urw.callerId == user1IpAddress && urw.resourceName == "resource1" && urw.warnPeriod == 0 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user1IpAddress) &&
                wl.usageWarningStatus(user1IpAddress).status.isNeutralAndWarningWasRemoved(curDate) &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus("resource1").isNeutralAndWarningWasRemoved(curDate) &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user1IpAddress).resourcesStatus("resource2").isWarnedTemporarily(curDate, 300) =>
          }
        }
      }

    }

    s"for $user2IpAddress" - {

      s"when sent WarnResourceForCaller command for resource1 for $user2IpAddress" - {
        s"should add warning for resource1 for $user2IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user2IpAddress, "resource1", warnFrom = Some(ZonedDateTime.now()))
          )
          expectMsgPF() {
            case urw: CallerResourceWarned if
              urw.callerId == user2IpAddress && urw.resourceName == "resource1" && urw.warnPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = true,
              onlyUnwarned = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user2IpAddress) &&
                wl.usageWarningStatus(user2IpAddress).status.isNeutral(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.size == 1 &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) =>
          }
        }
      }

      s"when sent WarnResourceForCaller command for resource2 for $user2IpAddress" - {
        s"should add warning for resource2 for $user2IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user2IpAddress, "resource2", warnFrom = Some(ZonedDateTime.now()), warnPeriod = Some(300))
          )
          expectMsgPF() {
            case urw: CallerResourceWarned if
              urw.callerId == user2IpAddress && urw.resourceName == "resource2" && urw.warnPeriod == 300 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = true,
              onlyUnwarned = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user2IpAddress) &&
                wl.usageWarningStatus(user2IpAddress).status.isNeutral(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource2").isWarnedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent UnwarnResourceForCaller command for resource2 for $user2IpAddress" - {
        s"should add unwarning and remove warning for resource2 for $user2IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            UnwarnResourceForCaller(user2IpAddress, "resource2", unwarnFrom = Some(ZonedDateTime.now()))
          )
          expectMsgPF() {
            case uruw: CallerResourceUnwarned if
              uruw.callerId == user2IpAddress && uruw.resourceName == "resource2" && uruw.unwarnPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user2IpAddress) &&
                wl.usageWarningStatus(user2IpAddress).status.isNeutral(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource2").isUnwarnedPermanently(curDate) =>
          }
        }
      }

      s"when sent WarnCaller command for $user2IpAddress" - {
        s"should add warning for $user2IpAddress and remove unwarning for resource2 for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnCaller(user2IpAddress, warnPeriod = Some(600))
          )
          expectMsgPF() {
            case uw: CallerWarned if
              uw.callerId == user2IpAddress && uw.warnPeriod == 600 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user2IpAddress) &&
                wl.usageWarningStatus(user2IpAddress).status.isWarnedTemporarily(curDate, 600) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource2").isNeutralAndUnwarningWasRemoved(curDate) =>
          }
        }
      }

      s"when sent WarnCaller command with warnPeriod = 0 and allWarnedResources = Y for $user2IpAddress" - {
        s"should remove warning for $user2IpAddress and resource1 for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnCaller(user2IpAddress, warnPeriod = Some(0), allWarnedResources = Some("Y"))
          )
          expectMsgPF() {
            case uw: CallerWarned if
              uw.callerId == user2IpAddress && uw.warnPeriod == 0 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user2IpAddress) &&
                wl.usageWarningStatus(user2IpAddress).status.isNeutralAndWarningWasRemoved(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource1").isNeutralAndWarningWasRemoved(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource2").isNeutralAndUnwarningWasRemoved(curDate) =>
          }
        }
      }

      s"when sent WarnResourceForCaller command for resource1 for $user2IpAddress again" - {
        s"should add warning for resource1 for $user2IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user2IpAddress, "resource1")
          )
          expectMsgPF() {
            case urw: CallerResourceWarned if
              urw.callerId == user2IpAddress && urw.resourceName == "resource1" && urw.warnPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user2IpAddress) &&
                wl.usageWarningStatus(user2IpAddress).status.isNeutralAndWarningWasRemoved(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource2").isNeutralAndUnwarningWasRemoved(curDate) =>
          }
        }
      }

      s"when sent UnwarnCaller command for $user2IpAddress" - {
        s"should add unwarning for $user2IpAddress and remove warning for resource1 for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            UnwarnCaller(user2IpAddress, unwarnFrom = Some(ZonedDateTime.now()))
          )
          expectMsgPF() {
            case uuw: CallerUnwarned if
              uuw.callerId == user2IpAddress && uuw.unwarnPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user2IpAddress) &&
                wl.usageWarningStatus(user2IpAddress).status.isUnwarned(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource1").isNeutralAndWarningWasRemoved(curDate) &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user2IpAddress).resourcesStatus("resource2").isNeutralAndUnwarningWasRemoved(curDate) =>
          }
        }
      }

      s"when sent WarnResourceForCaller command for resource1 for $user2IpAddress again" - {
        "should respond with BAD_REQUEST error status" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user2IpAddress, "resource1")
          )
          expectMsgPF() {
            case aer: ActorErrorResp if
              aer.exceptionClass == classOf[BadRequestErrorException] &&
                aer.statusCode == BAD_REQUEST.statusCode &&
                aer.statusMsg == Option("Resource cannot be warned for entity because entity is unwarned") =>
          }
        }
      }
    }

    s"for $user3IpAddress" - {

      s"when sent WarnResourceForCaller command for resource1 for $user3IpAddress" - {
        s"should add warning for resource1 for $user3IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user3IpAddress, "resource1")
          )
          expectMsgPF() {
            case urw: CallerResourceWarned if
              urw.callerId == user3IpAddress && urw.resourceName == "resource1" && urw.warnPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = true,
              onlyUnwarned = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user3IpAddress) &&
                wl.usageWarningStatus(user3IpAddress).status.isNeutral(curDate) &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.size == 1 &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) =>
          }
        }
      }

      s"when sent WarnResourceForCaller command for resource2 for $user3IpAddress" - {
        s"should add warning for resource2 for $user3IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user3IpAddress, "resource2", warnPeriod = Some(300))
          )
          expectMsgPF() {
            case urw: CallerResourceWarned if
              urw.callerId == user3IpAddress && urw.resourceName == "resource2" && urw.warnPeriod == 300 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = true,
              onlyUnwarned = false,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user3IpAddress) &&
                wl.usageWarningStatus(user3IpAddress).status.isNeutral(curDate) &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus("resource1").isWarnedPermanently(curDate) &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus("resource2").isWarnedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent UnwarnResourceForCaller command for resource1 for $user3IpAddress" - {
        s"should add unwarning and remove warning for resource1 for $user3IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            UnwarnResourceForCaller(user3IpAddress, "resource1", unwarnPeriod = Some(300))
          )
          expectMsgPF() {
            case uruw: CallerResourceUnwarned if
              uruw.callerId == user3IpAddress && uruw.resourceName == "resource1" && uruw.unwarnPeriod == 300 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = true,
              onlyActive = true,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user3IpAddress) &&
                wl.usageWarningStatus(user3IpAddress).status.isNeutral(curDate) &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.size == 1 && // filtered by onlyUnwarned = true
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus("resource1").isUnwarnedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent WarnCaller command for $user3IpAddress" - {
        s"should add warning for $user3IpAddress and remove unwarning for resource1 for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnCaller(user3IpAddress, warnFrom = Some(curDate))
          )
          expectMsgPF() {
            case uw: CallerWarned if
              uw.callerId == user3IpAddress && uw.warnPeriod == -1 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user3IpAddress) &&
                wl.usageWarningStatus(user3IpAddress).status.isWarnedPermanently(curDate) &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus("resource1").isNeutralAndUnwarningWasRemoved(curDate) &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus("resource2").isWarnedTemporarily(curDate, 300) =>
          }
        }
      }

      s"when sent UnwarnResourceForCaller command for resource2 for $user3IpAddress" - {
        "should respond with BAD_REQUEST error status" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            UnwarnResourceForCaller(user3IpAddress, "resource2", unwarnPeriod = Some(300))
          )
          expectMsgPF() {
            case aer: ActorErrorResp if
              aer.exceptionClass == classOf[BadRequestErrorException] &&
                aer.statusCode == BAD_REQUEST.statusCode &&
                aer.statusMsg == Option("Resource cannot be unwarned for entity because entity is warned") =>
          }
        }
      }

      s"when sent UnwarnCaller command for $user3IpAddress" - {
        s"should add unwarning and remove warning for $user3IpAddress and remove warning for resource2 for it" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            UnwarnCaller(user3IpAddress, unwarnPeriod = Some(600))
          )
          expectMsgPF() {
            case uuw: CallerUnwarned if
              uuw.callerId == user3IpAddress && uuw.unwarnPeriod == 600 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user3IpAddress) &&
                wl.usageWarningStatus(user3IpAddress).status.isUnwarnedTemporarily(curDate, 600) &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus("resource1").isNeutralAndUnwarningWasRemoved(curDate) &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus("resource2").isNeutralAndWarningWasRemoved(curDate) =>
          }
        }
      }

      s"when sent WarnResourceForCaller command with warnPeriod = 0 for resource1 for $user3IpAddress" - {
        "should respond with BAD_REQUEST error status" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnResourceForCaller(user3IpAddress, "resource1", warnPeriod = Some(0))
          )
          expectMsgPF() {
            case aer: ActorErrorResp if
              aer.exceptionClass == classOf[BadRequestErrorException] &&
                aer.statusCode == BAD_REQUEST.statusCode &&
                aer.statusMsg == Option("Resource cannot be warned for entity because entity is unwarned") =>
          }
        }
      }

      s"when sent WarnCaller command for $user3IpAddress" - {
        s"should add warning and remove unwarning for $user3IpAddress" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          singletonParentProxy ! ForResourceWarningStatusMngr(
            WarnCaller(user3IpAddress, warnFrom = Some(curDate), warnPeriod = Some(600))
          )
          expectMsgPF() {
            case uw: CallerWarned if
              uw.callerId == user3IpAddress && uw.warnPeriod == 600 =>
          }
          singletonParentProxy ! ForResourceWarningStatusMngr(
            GetWarnedList(
              onlyWarned = false,
              onlyUnwarned = false,
              onlyActive = false,
              inChunks = false
            )
          )
          expectMsgPF() {
            case wl: UsageWarningStatusChunk if
              wl.usageWarningStatus.contains(user3IpAddress) &&
                wl.usageWarningStatus(user3IpAddress).status.isWarnedTemporarily(curDate, 600) &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.size == 2 &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource1") &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus("resource1").isNeutralAndUnwarningWasRemoved(curDate) &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus.contains("resource2") &&
                wl.usageWarningStatus(user3IpAddress).resourcesStatus("resource2").isNeutralAndWarningWasRemoved(curDate) =>
          }
        }
      }

    }

  }

}
