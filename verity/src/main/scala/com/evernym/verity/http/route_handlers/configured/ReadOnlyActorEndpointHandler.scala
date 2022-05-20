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

  protected def readOnlyActorResponseHandler(inHtml: String): PartialFunction[Any, ToResponseMarshallable] = {
    case sd: SummaryData =>
      handleExpectedResponse(sd)
    case resp: PersistentDataResp if inHtml != YES =>
      handleExpectedResponse(resp.toRecords.mkString("\n"))
    case resp: PersistentDataResp if inHtml == YES =>
      val respStr = resp.toRecords.mkString("<br><br>")
      val entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, respStr)
      HttpResponse(StatusCodes.OK, entity = entity)
    case e =>
      handleUnexpectedResponse(e)
  }

  private def getPersistentActorEvents(cmd: ActorCommand)(reload: String,
                                                  actorTypeName: String,
                                                  actorEntityId: String,
                                                  recoverFromSnapshot: String,
                                                  persEncKey: Option[String]): Future[Any] = {
    val actorParam = ActorParam(actorTypeName, actorEntityId, recoverFromSnapshot == YES, persEncKey)

    if (reload == YES) {
      platform.nodeSingleton ! PersistentActorQueryParam(actorParam, Stop())
    }
    platform.nodeSingleton ? PersistentActorQueryParam(actorParam, cmd)
  }

  protected val persistentActorMaintenanceRoutes: Route =
    handleRestrictedRequest(exceptionHandler) { (_, _) =>
      pathPrefix("agency" / "internal" / "maintenance" / "persistent-actor" / Segment / Segment / "data") { (actorTypeName, actorEntityId) =>
        path("summary") {
          (get & pathEnd) {
            parameters(Symbol("reload") ? NO, Symbol("recoverFromSnapshot") ? YES, Symbol("persEncKey").?) {
              (reload, recoverFromSnapshot, persEncKey) =>
                complete {
                  getPersistentActorEvents(SendSummary)(reload, actorTypeName, actorEntityId, recoverFromSnapshot, persEncKey).map {
                    readOnlyActorResponseHandler(NO)
                  }
                }
            }
          }
        } ~
          path("aggregated") {
            (get & pathEnd) {
              parameters(Symbol("asHtml") ? NO, Symbol("reload") ? NO, Symbol("recoverFromSnapshot") ? YES, Symbol("persEncKey").?) {
                (inHtml, reload, recoverFromSnapshot, persEncKey) =>
                  complete {
                    getPersistentActorEvents(SendAggregated)(reload, actorTypeName, actorEntityId, recoverFromSnapshot, persEncKey).map {
                      readOnlyActorResponseHandler(inHtml)
                    }
                  }
              }
            }
          } ~
          path("all") {
            (get & pathEnd) {
              parameters(Symbol("asHtml") ? NO, Symbol("withData") ? NO, Symbol("reload") ? NO, Symbol("recoverFromSnapshot") ? YES, Symbol("persEncKey").?) {
                (inHtml, withData, reload, recoverFromSnapshot, persEncKey) =>
                  complete {
                    getPersistentActorEvents(SendAll(withData))(reload, actorTypeName, actorEntityId, recoverFromSnapshot, persEncKey).map {
                      readOnlyActorResponseHandler(inHtml)
                    }
                  }
              }
            }
          }
      }
    }
}
