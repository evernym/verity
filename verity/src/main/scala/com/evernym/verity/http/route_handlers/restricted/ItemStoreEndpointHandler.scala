package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import com.evernym.verity.actor.cluster_singleton.watcher.{ForEntityItemWatcher, GetItems}
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor
import com.evernym.verity.item_store.ItemStore

import scala.concurrent.Future

trait ItemStoreEndpointHandler extends BaseRequestHandler {
  this: PlatformWithExecutor =>

  protected def getItems: Future[Any] = {
    platform.singletonParentProxy ? ForEntityItemWatcher(GetItems)
  }

  protected val itemStoreRoutes: Route =
    handleRestrictedRequest(exceptionHandler) { (_, _) =>
      pathPrefix("agency" / "internal" / "item-store") {
        (get & pathEnd) {
          complete {
            getItems.map[ToResponseMarshallable] {
              case items: ItemStore.Replies.Items => handleExpectedResponse(items)
              case e => handleUnexpectedResponse(e)
            }
          }
        }
      }
    }
}
