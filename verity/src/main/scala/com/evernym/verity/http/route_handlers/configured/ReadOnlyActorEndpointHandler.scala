package com.evernym.verity.http.route_handlers.configured

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import com.evernym.verity.actor.base.Stop
import com.evernym.verity.actor.maintenance._
import com.evernym.verity.actor.node_singleton.PersistentActorQueryParam
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor

import scala.concurrent.Future

trait ReadOnlyActorEndpointHandler
  extends BaseRequestHandler {
  this: PlatformWithExecutor =>

  def getPersistentActorEvents(reload: String, actorParam: ActorParam, cmd: Any): Future[Any] = {
    if (reload == YES) {
      platform.nodeSingleton ! PersistentActorQueryParam(actorParam, Stop())
    }
    platform.nodeSingleton ? PersistentActorQueryParam(actorParam, cmd)
  }

  private def completeRequest(actorParam: ActorParam, cmd: Any, inHtml: String, reload: String): Route = {
    complete {
      getPersistentActorEvents(reload, actorParam, cmd).map[ToResponseMarshallable] {
        case sd: SummaryData => handleExpectedResponse(sd)
        case resp: PersistentDataResp =>
          if (inHtml == YES) {
            val respStr = resp.toRecords.mkString("<br><br>")
            HttpResponse.apply(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, respStr))
          } else {
            handleExpectedResponse(resp.toRecords.mkString("\n"))
          }
        case e => handleUnexpectedResponse(e)
      }
    }
  }

  private val basePath = "agency" / "internal" / "maintenance" / "persistent-actor"

  protected val persistentActorMaintenanceRoutes: Route =
    handleRestrictedRequest(exceptionHandler) { (_, _) =>
      pathPrefix(basePath / Segment / Segment / "data") { (actorTypeName, actorEntityId) =>
        path("summary") {
          (get & pathEnd) {
            parameters(Symbol("reload") ? "N", Symbol("recoverFromSnapshot") ? "Y", Symbol("persEncKey").?) {
              (reload, recoverFromSnapshot, persEncKey) =>
                val actorParam = ActorParam(actorTypeName, actorEntityId, recoverFromSnapshot == YES, persEncKey)
                completeRequest(actorParam, SendSummary, inHtml = NO, reload)
            }
          }
        } ~
          path("aggregated") {
            (get & pathEnd) {
              parameters(Symbol("asHtml") ? "N", Symbol("reload") ? "N", Symbol("recoverFromSnapshot") ? "Y", Symbol("persEncKey").?) {
                (inHtml, reload, recoverFromSnapshot, persEncKey) =>
                  val actorParam = ActorParam(actorTypeName, actorEntityId, recoverFromSnapshot == YES, persEncKey)
                  completeRequest(actorParam, SendAggregated, inHtml, reload)
              }
            }
          } ~
          path("all") {
            (get & pathEnd) {
              parameters(Symbol("asHtml") ? "N", Symbol("withData") ? "N", Symbol("reload") ? "N", Symbol("recoverFromSnapshot") ? "Y", Symbol("persEncKey").?) {
                (inHtml, withData, reload, recoverFromSnapshot, persEncKey) =>
                  val actorParam = ActorParam(actorTypeName, actorEntityId, recoverFromSnapshot == YES, persEncKey)
                  completeRequest(actorParam, SendAll(withData), inHtml, reload)
              }
            }
          }
      }
    }
}
