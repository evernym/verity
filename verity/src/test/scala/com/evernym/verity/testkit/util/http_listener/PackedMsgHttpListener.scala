package com.evernym.verity.testkit.util.http_listener

import akka.http.scaladsl.model.{HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractRequest, logRequestResult, pathPrefix, post, reject}
import akka.http.scaladsl.server.Route
import com.evernym.verity.http.common.HttpCustomTypes


trait PackedMsgHttpListener
  extends BaseHttpListener[Array[Byte]]{

  import akka.http.scaladsl.model.StatusCodes._

  lazy val listeningUrl: String = s"${listeningEndpoint.url}/packed-msg"

  def handleAgentMsgReqForOctetStreamContentType: Route = {
    import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller
    entity(as[Array[Byte]]) { data =>
      complete {
        addToMsgs(data)
        OK
      }
    }
  }

  def agentMsgHandler(implicit req: HttpRequest): Route = {
    req.entity.contentType.mediaType match {
      case MediaTypes.`application/octet-stream` | HttpCustomTypes.MEDIA_TYPE_SSI_AGENT_WIRE =>
        handleAgentMsgReqForOctetStreamContentType
      case _ =>
        // non-binary message received
        reject
    }
  }

  val edgeRoute: Route =
    logRequestResult("edge") {
      pathPrefix(s"${listeningEndpoint.path}") {
        extractRequest { implicit req: HttpRequest =>
          post {
            agentMsgHandler
          }
        }
      }
    }

  init()
}
