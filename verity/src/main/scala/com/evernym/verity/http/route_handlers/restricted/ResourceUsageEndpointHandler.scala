package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives.{complete, pathEnd, _}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.{BlockCaller, BlockResourceForCaller, GetBlockedList, UnblockCaller, UnblockResourceForCaller, UsageBlockingStatusChunk}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.{GetWarnedList, UnwarnCaller, UnwarnResourceForCaller, UsageWarningStatusChunk, WarnCaller, WarnResourceForCaller}
import com.evernym.verity.actor.cluster_singleton.{ForResourceBlockingStatusMngr, ForResourceWarningStatusMngr}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.resourceusagethrottling.tracking.{GetAllResourceUsages, ResourceUsages}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

import scala.concurrent.Future


trait ResourceUsageEndpointHandler { this: HttpRouteWithPlatform =>

  implicit val responseTimeout: Timeout

  protected def getBlockedResources(gblc: GetBlockedList): Future[Any] = {
    platform.singletonParentProxy ? ForResourceBlockingStatusMngr(gblc)
  }

  protected def getWarnedResources(gwlc: GetWarnedList): Future[Any] = {
    platform.singletonParentProxy ? ForResourceWarningStatusMngr(gwlc)
  }

  protected def getResourceUsages(callerId: String): Future[Any] = {
    platform.resourceUsageTrackerRegion ? ForIdentifier(callerId, GetAllResourceUsages)
  }

  protected def updateCallerDetail(id: String, uvd: UpdateViolationDetail): Future[Any] = {
    if (uvd.msgType == "block") {
      platform.singletonParentProxy ? ForResourceBlockingStatusMngr(
        BlockCaller(id, blockPeriod = uvd.period, allBlockedResources = uvd.allResources))
    } else if (uvd.msgType == "unblock") {
      platform.singletonParentProxy ? ForResourceBlockingStatusMngr(
        UnblockCaller(id, unblockPeriod = uvd.period, allBlockedResources = uvd.allResources))
    } else if (uvd.msgType == "warn") {
      platform.singletonParentProxy ? ForResourceWarningStatusMngr(
        WarnCaller(id, warnPeriod = uvd.period, allWarnedResources = uvd.allResources))
    } else if (uvd.msgType == "unwarn") {
      platform.singletonParentProxy ? ForResourceWarningStatusMngr(
        UnwarnCaller(id, unwarnPeriod = uvd.period, allWarnedResources = uvd.allResources))
    } else {
      Future.successful(new BadRequestErrorException(UNSUPPORTED_MSG_TYPE.statusCode, Option(s"unsupported message type: ${uvd.msgType}")))
    }
  }

  protected def updateResourceDetail(callerId: String, resource: String, urd: UpdateViolationDetail): Future[Any] = {
    if (urd.msgType == "block") {
      platform.singletonParentProxy ? ForResourceBlockingStatusMngr(
        BlockResourceForCaller(callerId, resource, blockPeriod = urd.period))
    } else if (urd.msgType == "unblock") {
      platform.singletonParentProxy ? ForResourceBlockingStatusMngr(
        UnblockResourceForCaller(callerId, resource, unblockPeriod = urd.period))
    } else if (urd.msgType == "warn") {
      platform.singletonParentProxy ? ForResourceWarningStatusMngr(
        WarnResourceForCaller(callerId, resource, warnPeriod = urd.period))
    } else if (urd.msgType == "unwarn") {
      platform.singletonParentProxy ? ForResourceWarningStatusMngr(
        UnwarnResourceForCaller(callerId, resource, unwarnPeriod = urd.period))
    } else {
      Future(new BadRequestErrorException(UNSUPPORTED_MSG_TYPE.statusCode, Option(s"unsupported message type: ${urd.msgType}")))
    }
  }

  protected def updateResourceUsageLimits(callerId: String, url: UpdateResourcesUsageLimit): Future[Any] = {
    platform.resourceUsageTrackerRegion ? ForIdentifier(callerId, url)
  }

  protected def updateResourceUsageCounters(callerId: String, urc: UpdateResourcesUsageCounter): Future[Any] = {
    platform.resourceUsageTrackerRegion ? ForIdentifier(callerId, urc)
  }

  protected def handleGetWarnedList(onlyWarned: String, onlyUnwarned: String, onlyActive: String,
                          ids: Option[String], resourceNames: Option[String]): Route = {
    complete {
      val gwlc = GetWarnedList(onlyWarned, onlyUnwarned, onlyActive, inChunks = false, ids, resourceNames)
      getWarnedResources(gwlc).map[ToResponseMarshallable] {
        case bsr: UsageWarningStatusChunk => handleExpectedResponse(bsr.usageWarningStatus)
        case e => handleUnexpectedResponse(e)
      }
    }
  }

  protected def handleGetBlockedList(onlyBlocked: String, onlyUnblocked: String, onlyActive: String,
                           ids: Option[String], resourceNames: Option[String]): Route = {
    complete {
      val gblc = GetBlockedList(onlyBlocked, onlyUnblocked, onlyActive, inChunks = false, ids, resourceNames)
      getBlockedResources(gblc).map[ToResponseMarshallable] {
        case bsr: UsageBlockingStatusChunk => handleExpectedResponse(bsr.usageBlockingStatus)
        case e => handleUnexpectedResponse(e)
      }
    }
  }

  protected def handleGetResourceUsages(callerId: String): Route = {
    complete {
      getResourceUsages(callerId).map[ToResponseMarshallable] {
        case cru: ResourceUsages => handleExpectedResponse(cru)
        case e => handleUnexpectedResponse(e)
      }
    }
  }

  protected def handleUpdateCallerDetail(callerId: String, uvd: UpdateViolationDetail): Route = {
    complete {
      updateCallerDetail(callerId, uvd).map[ToResponseMarshallable] {
        case _@(_: CallerBlocked
                | _: CallerUnblocked
                | _: CallerWarned
                | _: CallerUnwarned) => OK
        case e => handleUnexpectedResponse(e)
      }
    }
  }

  protected def handleUpdateResourceUsageCounterDetails(callerId: String, uuc: UpdateResourcesUsageCounter): Route = {
    complete {
      updateResourceUsageCounters(callerId, uuc).map[ToResponseMarshallable] {
        case Done => OK
        case e => handleUnexpectedResponse(e)
      }
    }
  }

  protected def handleUpdateResourceDetail(callerId: String, resource: String, uvd: UpdateViolationDetail): Route = {
    complete {
      updateResourceDetail(callerId, resource, uvd).map[ToResponseMarshallable] {
        case _@(_: CallerResourceBlocked
                | _: CallerResourceUnblocked
                | _: CallerResourceWarned
                | _: CallerResourceUnwarned) => OK
        case e => handleUnexpectedResponse(e)
      }
    }
  }

  protected val resourceUsageRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("agency" / "internal" / "resource-usage") {
          extractRequest { implicit req =>
            extractClientIP { implicit remoteAddress =>
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              pathPrefix("warned") {
                (get & pathEnd) {
                  parameters('onlyWarned ? "N", 'onlyUnwarned ? "N", 'onlyActive ? "Y", 'ids.?, 'resourceNames.?) {
                    (onlyWarned, onlyUnwarned, onlyActive, ids, resourceNames) =>
                      handleGetWarnedList(onlyWarned, onlyUnwarned, onlyActive, ids, resourceNames)
                  }
                }
              } ~
                pathPrefix("blocked") {
                  (get & pathEnd) {
                    parameters('onlyBlocked ? "N", 'onlyUnblocked ? "N", 'onlyActive ? "Y", 'ids.?, 'resourceNames.?) {
                      (onlyBlocked, onlyUnblocked, onlyActive, ids, resourceNames) =>
                        handleGetBlockedList(onlyBlocked, onlyUnblocked, onlyActive, ids, resourceNames)
                    }
                  }
                } ~
                pathPrefix("id") {
                  pathPrefix(Segment) { callerId =>
                    (get & pathEnd) {
                      handleGetResourceUsages(callerId)
                    } ~
                      (put & pathEnd & entityAs[UpdateViolationDetail]) { uvd =>
                        handleUpdateCallerDetail(callerId, uvd)
                      } ~
                      pathPrefix("counter") {
                        (put & pathEnd & entityAs[UpdateResourcesUsageCounter]) { uruc =>
                          handleUpdateResourceUsageCounterDetails(callerId, uruc)
                        }
                      } ~
                      pathPrefix("resource") {
                        pathPrefix(Segment) { resource =>
                          (put & pathEnd & entityAs[UpdateViolationDetail]) { uvd =>
                            handleUpdateResourceDetail(callerId, resource, uvd)
                          }
                        }
                      }
                  }
                }
            }
          }
        }
      }
    }
}


case class UpdateViolationDetail(msgType: String,
                                 @JsonDeserialize(contentAs = classOf[Long]) period: Option[Long],
                                 allResources: Option[String])

case class ResourceUsageLimitDetail(resourceName: String, bucketId: Int,
                                    newLimit: Option[Int]=None, addToCurrentUsedCount: Option[Int]=None) {
  require(! (newLimit.isEmpty && addToCurrentUsedCount.isEmpty),
    "one and only one of these should be specified: 'newLimit' or 'addToCurrentUsedCount'")
}
case class UpdateResourcesUsageLimit(resourceUsageLimits: List[ResourceUsageLimitDetail])


case class ResourceUsageCounterDetail(resourceName: String, bucketId: Int, newCount: Option[Int])
case class UpdateResourcesUsageCounter(resourceUsageCounters: List[ResourceUsageCounterDetail]) extends ActorMessage
