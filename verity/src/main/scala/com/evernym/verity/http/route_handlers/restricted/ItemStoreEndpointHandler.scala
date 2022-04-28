package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import com.evernym.verity.actor.cluster_singleton.watcher.{ForEntityItemWatcher, GetItems}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.item_store.ItemStore

import scala.concurrent.Future

trait ItemStoreEndpointHandler {
  this: HttpRouteWithPlatform =>

  protected def getItems(): Future[Any] = {
    platform.singletonParentProxy ? ForEntityItemWatcher(GetItems)
  }

  protected val itemStoreRoutes: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        extractRequest { implicit req =>
          extractClientIP { implicit remoteAddress =>
            pathPrefix("agency" / "internal" / "item-store") {
              checkIfAddressAllowed(remoteAddress, req.uri)
              (get & pathEnd) {
                complete {
                  getItems().map[ToResponseMarshallable] {
                    case items: ItemStore.Replies.Items => handleExpectedResponse(items)
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
