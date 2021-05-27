package com.evernym.integrationtests.e2e.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractRequest, logRequestResult, path, post, reject}
import akka.http.scaladsl.server.Route
import com.evernym.verity.UrlParam
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.HttpServerUtil
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

import scala.collection.immutable.Queue


class HttpListener(val appConfig: AppConfig,
                   val listeningPort: Int,
                   endpoint: UrlParam,
                   override implicit val system: ActorSystem) extends HttpServerUtil {
  import akka.http.scaladsl.model.StatusCodes._

  val logger: Logger = getLoggerByClass(classOf[HttpListener])

  private var queue: Queue[Array[Byte]] = Queue.empty

  def logMsg(msg: String): Unit = {
    logger.info(s"[listener-localhost:$listeningPort]: " + msg)
  }

  def dequeueOpt: Option[Array[Byte]] = {
    //logMsg(s"queue size during deque: " + queue.length)
    val resultOpt = queue.dequeueOption
    resultOpt.foreach { res =>
      queue = res._2
      //logMsg(s"queue size after removing item: " + queue.length)
    }
    resultOpt.map(_._1)
  }

  def dequeue: Array[Byte] = {
    dequeueOpt.getOrElse(throw new RuntimeException("no msg received yet"))
  }


  def handleBinaryMsgReqForOctetStreamContentType: Route = {
    import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller
    entity(as[Array[Byte]]) { data =>
      complete {
        //logMsg(s"new message received")
        queue = queue.enqueue(data)
        //logMsg(s"queue size after receiving new item: " + queue.length)
        OK
      }
    }
  }

  def agentMsgHandler(implicit req: HttpRequest): Route = {
    req.entity.contentType.mediaType match {
      case MediaTypes.`application/octet-stream` =>
        handleBinaryMsgReqForOctetStreamContentType
      case _ =>
        logMsg("non binary message received")
        reject
    }
  }

  val route: Route =
    logRequestResult("http-listener") {
      path("msg") {
        extractRequest { implicit req: HttpRequest =>
          post {
            agentMsgHandler
          }
        }
      }
    }

  val url = endpoint.url + "msg"
  val bindFuture = Http().newServerAt("localhost", listeningPort).bind(corsHandler(route))

  def stopHttpListener(): Unit = {
    import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
    bindFuture.flatMap(_.unbind())
  }
}
