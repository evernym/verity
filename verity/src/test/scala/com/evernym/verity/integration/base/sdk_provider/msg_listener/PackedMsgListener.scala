package com.evernym.verity.integration.base.sdk_provider.msg_listener

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractRequest, logRequestResult, pathPrefix, post, reject, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.HttpCustomTypes

class PackedMsgListener(val port: Int)
                       (implicit val appConfig: AppConfig, val system: ActorSystem)
  extends MsgListenerBase[Array[Byte]] {

  private def agentMsgHandler(implicit req: HttpRequest): Route = {
    req.entity.contentType.mediaType match {
      case MediaTypes.`application/octet-stream` | HttpCustomTypes.MEDIA_TYPE_SSI_AGENT_WIRE =>
        entity(as[Array[Byte]]) { data =>
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

  override val edgeRoute: Route =
    logRequestResult("edge") {
      pathPrefix(s"$baseEndpointPath") {
        extractRequest { implicit req: HttpRequest =>
          post {
            agentMsgHandler
          }
        }
      }
    }

  startHttpServer()
}
