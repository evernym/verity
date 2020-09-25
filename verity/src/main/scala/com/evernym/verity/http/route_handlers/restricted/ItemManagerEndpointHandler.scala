package com.evernym.verity.http.route_handlers.restricted

import akka.pattern.ask
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.{Gone, NotFound}
import akka.http.scaladsl.server.Directives.{complete, extractClientIP, extractRequest, get, handleExceptions, logRequestResult, pathPrefix}
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.itemmanager._
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform

import scala.concurrent.Future

trait ItemManagerEndpointHandler { this: HttpRouteWithPlatform =>

  def getActiveItemContainerList(itemManagerId: String): Future[Any] = {
    platform.itemManagerRegion ? ForIdentifier(itemManagerId, ExternalCmdWrapper(GetActiveContainers, None))
  }

  def getStatusOfItemManager(itemManagerId: String): Future[Any] = {
    platform.itemManagerRegion ? ForIdentifier(itemManagerId, ExternalCmdWrapper(GetState, None))
  }

  def getStatusOfItemContainer(itemContainerId: String): Future[Any] = {
    platform.itemContainerRegion ? ForIdentifier(itemContainerId, ExternalCmdWrapper(GetState, None))
  }

  protected val itemManagerRoutes: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        extractRequest { implicit req =>
          extractClientIP { implicit remoteAddress =>
            pathPrefix("agency" / "internal" / "item-manager") {
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              pathPrefix(Segment) { itemManagerId =>
                (get & pathEnd) {
                  complete {
                    getStatusOfItemManager(itemManagerId).map[ToResponseMarshallable] {
                      case imsd: ItemManagerStateDetail => handleExpectedResponse(imsd)
                      case ItemManagerConfigNotYetSet => NotFound
                      case e => handleUnexpectedResponse(e)
                    }
                  }
                } ~
                  pathPrefix("active-container") {
                    (get & pathEnd) {
                      complete {
                        getActiveItemContainerList(itemManagerId).map[ToResponseMarshallable] {
                          case aci: ActiveContainerStatus => handleExpectedResponse(aci)
                          case ItemManagerConfigNotYetSet | NoItemsFound => NotFound
                          case e => handleUnexpectedResponse(e)
                        }
                      }
                    }
                  }
              }
            } ~
              pathPrefix("agency" / "internal" / "item-container") {
                checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
                pathPrefix(Segment) { itemContainerId =>
                  (get & pathEnd) {
                    complete {
                      getStatusOfItemContainer(itemContainerId).map[ToResponseMarshallable] {
                        case icsd: ItemContainerState => handleExpectedResponse(icsd)
                        case ItemContainerStaleOrConfigNotYetSet => NotFound
                        case ItemContainerDeleted => Gone
                        case e => handleUnexpectedResponse(e)
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
