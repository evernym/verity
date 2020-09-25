package com.evernym.integrationtests.e2e.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractRequest, logRequestResult, path, post, reject}
import akka.http.scaladsl.server.Route
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.HttpBindUtil
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.UrlDetail
import com.typesafe.scalalogging.Logger

import scala.collection.immutable.Queue


class HttpListener(val appConfig: AppConfig, val listeningPort: Int, endpoint: UrlDetail) extends HttpBindUtil {
  import akka.http.scaladsl.model.StatusCodes._

  private var queue: Queue[Array[Byte]] = Queue.empty


  def logMsg(msg: String): Unit = {
    println(s"[listener-localhost:$listeningPort]: " + msg)
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

  lazy val url = endpoint.url + "msg"
  val logger: Logger = getLoggerByName("http-listener")
  override lazy implicit val system: ActorSystem = ActorSystem("http-listener", appConfig.getLoadedConfig)
  val bindFuture = Http().bindAndHandle(corsHandler(route), "localhost", listeningPort)

  def stopHttpListener(): Unit = {
    import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
    bindFuture.flatMap(_.unbind())
  }
}
