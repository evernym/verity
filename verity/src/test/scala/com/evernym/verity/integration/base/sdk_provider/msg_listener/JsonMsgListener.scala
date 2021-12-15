package com.evernym.verity.integration.base.sdk_provider.msg_listener

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.{HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractRequest, logRequestResult, pathPrefix, post, reject, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.integration.base.sdk_provider.OAuthParam

class JsonMsgListener(val port: Int,
                      val oAuthParam: Option[OAuthParam] = None)
                     (implicit val actorSystem: ActorSystem)
  extends MsgListenerBase[String] {

  override val msgRoute: Route =
    logRequestResult("msg") {
      pathPrefix(s"$webhookEndpointPath") {
        extractRequest { implicit req: HttpRequest =>
          extractCredentials { cred =>
            post {
              agentMsgHandler(cred)
            }
          }
        }
      }
    }

  private def agentMsgHandler(cred: Option[HttpCredentials])(implicit req: HttpRequest): Route = {
    req.entity.contentType.mediaType match {
      case MediaTypes.`application/json` =>
        entity(as[String]) { data =>
          complete {
            handleIncomingMsg(data, cred)
          }
        }
      case _ =>
        // non-binary message received
        reject
    }
  }

  startHttpServer()
}