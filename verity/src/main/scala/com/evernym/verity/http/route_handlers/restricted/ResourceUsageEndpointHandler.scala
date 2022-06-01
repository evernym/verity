package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor._
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking._
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning._
import com.evernym.verity.actor.cluster_singleton.{ForResourceBlockingStatusMngr, ForResourceWarningStatusMngr}
import com.evernym.verity.actor.resourceusagethrottling.tracking.{GetAllResourceUsages, ResourceUsages}
import com.evernym.verity.http.HttpUtil.entityAs
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor
import com.evernym.verity.http.route_handlers.restricted.models.{UpdateResourcesUsageCounter, UpdateResourcesUsageLimit, UpdateViolationDetail}
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.util2.Status._

import scala.concurrent.Future

trait ResourceUsageEndpointHandler extends BaseRequestHandler with HasExecutionContextProvider {
  this: PlatformWithExecutor =>

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
        UnblockCaller(id, unblockPeriod = uvd.period))
    } else if (uvd.msgType == "warn") {
      platform.singletonParentProxy ? ForResourceWarningStatusMngr(
        WarnCaller(id, warnPeriod = uvd.period, allWarnedResources = uvd.allResources))
    } else if (uvd.msgType == "unwarn") {
      platform.singletonParentProxy ? ForResourceWarningStatusMngr(
        UnwarnCaller(id, unwarnPeriod = uvd.period))
    } else {
      Future.successful(new BadRequestErrorException(UNSUPPORTED_MSG_TYPE.statusCode, Option(s"unsupported message type: ${uvd.msgType}")))
    }
  }

  protected def updateResourceDetail(callerId: String, resource: String, uvd: UpdateViolationDetail): Future[Any] = {
    if (uvd.msgType == "block") {
      platform.singletonParentProxy ? ForResourceBlockingStatusMngr(
        BlockResourceForCaller(callerId, resource, blockPeriod = uvd.period))
    } else if (uvd.msgType == "unblock") {
      platform.singletonParentProxy ? ForResourceBlockingStatusMngr(
        UnblockResourceForCaller(callerId, resource, unblockPeriod = uvd.period))
    } else if (uvd.msgType == "warn") {
      platform.singletonParentProxy ? ForResourceWarningStatusMngr(
        WarnResourceForCaller(callerId, resource, warnPeriod = uvd.period))
    } else if (uvd.msgType == "unwarn") {
      platform.singletonParentProxy ? ForResourceWarningStatusMngr(
        UnwarnResourceForCaller(callerId, resource, unwarnPeriod = uvd.period))
    } else {
      Future(new BadRequestErrorException(UNSUPPORTED_MSG_TYPE.statusCode, Option(s"unsupported message type: ${uvd.msgType}")))
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
    handleRestrictedRequest(exceptionHandler) { (_, _) =>
      pathPrefix("agency" / "internal" / "resource-usage") {
        pathPrefix("warned") {
          (get & pathEnd) {
            parameters(Symbol("onlyWarned") ? "N", Symbol("onlyUnwarned") ? "N", Symbol("onlyActive") ? "Y", Symbol("ids").?, Symbol("resourceNames").?) {
              (onlyWarned, onlyUnwarned, onlyActive, ids, resourceNames) =>
                handleGetWarnedList(onlyWarned, onlyUnwarned, onlyActive, ids, resourceNames)
            }
          }
        } ~
          pathPrefix("blocked") {
            (get & pathEnd) {
              parameters(Symbol("onlyBlocked") ? "N", Symbol("onlyUnblocked") ? "N", Symbol("onlyActive") ? "Y", Symbol("ids").?, Symbol("resourceNames").?) {
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
                  pathPrefix(Segments(1, 2)) { resourceSegments =>
                    (put & pathEnd & entityAs[UpdateViolationDetail]) { uvd =>
                      handleUpdateResourceDetail(callerId, resourceSegments.mkString("/"), uvd)
                    }
                  }
                }
            }
          }
      }
    }
}
