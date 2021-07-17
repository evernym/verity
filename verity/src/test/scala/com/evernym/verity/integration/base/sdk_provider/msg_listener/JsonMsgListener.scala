package com.evernym.verity.integration.base.sdk_provider.msg_listener

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.{OK, Unauthorized}
import akka.http.scaladsl.model.{HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractRequest, logRequestResult, pathPrefix, post, reject, _}
import akka.http.scaladsl.server.Route

import scala.concurrent.duration._

class JsonMsgListener(val port: Int,
                      val checkAuthToken: Boolean = false,
                      val tokenExpiresInDuration: Option[FiniteDuration] = None)
                     (implicit val actorSystem: ActorSystem)
  extends MsgListenerBase[String] {

  override val msgRoute: Route =
    logRequestResult("msg") {
      pathPrefix(s"$webhookEndpointPath") {
        extractRequest { implicit req: HttpRequest =>
          extractCredentials { cred =>
            post {
              if (checkAuthToken) {
                if (hasValidToken(cred)) agentMsgHandler
                else complete(Unauthorized)
              } else {
                _plainMsgsSinceLastReset = _plainMsgsSinceLastReset + 1
                agentMsgHandler
              }
            }
          }
        }
      }
    }

  private def agentMsgHandler(implicit req: HttpRequest): Route = {
    req.entity.contentType.mediaType match {
      case MediaTypes.`application/json` =>
        entity(as[String]) { data =>
          complete {
            receiveMsg(data)
            OK
          }
        }
      case _ =>
        // non-binary message received
        reject
    }
  }

  startHttpServer()
}