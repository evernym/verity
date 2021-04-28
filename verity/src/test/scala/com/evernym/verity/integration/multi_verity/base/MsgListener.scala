package com.evernym.verity.integration.multi_verity.base

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractRequest, logRequestResult, pathPrefix, post, reject, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.{HttpCustomTypes, HttpServerUtil}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

import java.util.concurrent.LinkedBlockingDeque
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag


class MsgListener(port: Int, unpacker: Unpacker)
                 (implicit val appConfig: AppConfig, val system: ActorSystem)
  extends HttpServerUtil {

  def expectMsg[T: ClassTag](max: Duration): T = {
    val m = Option {
      if (max == Duration.Zero) {
        queue.pollFirst
      } else if (max.isFinite) {
        queue.pollFirst(max.length, max.unit)
      } else {
        queue.takeFirst
      }
    }
    val msg = m.getOrElse(throw new Exception(s"timeout ($max) during expectMsg while waiting for message"))
    unpacker.unpackMsg(msg)
  }

  private def receiveMsg(msg: Array[Byte]): Unit = queue.offerFirst(msg)

  private def handleAgentMsgReqForOctetStreamContentType: Route = {
    import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller
    entity(as[Array[Byte]]) { data =>
      complete {
        receiveMsg(data)
        OK
      }
    }
  }

  private def agentMsgHandler(implicit req: HttpRequest): Route = {
    req.entity.contentType.mediaType match {
      case MediaTypes.`application/octet-stream` | HttpCustomTypes.MEDIA_TYPE_SSI_AGENT_WIRE =>
        handleAgentMsgReqForOctetStreamContentType
      case _ =>
        // non-binary message received
        reject
    }
  }

  private val edgeRoute: Route =
    logRequestResult("edge") {
      pathPrefix(s"$path") {
        extractRequest { implicit req: HttpRequest =>
          post {
            agentMsgHandler
          }
        }
      }
    }

  Http().newServerAt("localhost", port).bind(corsHandler(edgeRoute))

  def endpoint = s"http://localhost:$port/$path"

  override def logger: Logger = getLoggerByClass(this.getClass)

  private val path: String = "edge-" + port
  private val queue: LinkedBlockingDeque[Array[Byte]] = new LinkedBlockingDeque[Array[Byte]]()
}
