package com.evernym.verity.testkit.util.http_listener

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractRequest, handleExceptions, logRequestResult, pathPrefix, post, reject}
import akka.http.scaladsl.server.Route
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.util2.HasExecutionContextProvider

import scala.concurrent.{ExecutionContext, Future}

trait PushNotifMsgHttpListener
  extends BaseHttpListener[String]
    with HasExecutionContextProvider {

  import akka.http.scaladsl.model.StatusCodes._
  implicit lazy val executionContext: ExecutionContext = futureExecutionContext

  lazy val listeningUrl: String = s"FCM:${listeningEndpoint.url}"

  def handleJsonMsg(jsonMsg: String): Future[Either[RuntimeException, String]] = {
    addToMsgs(jsonMsg)
    Future.successful(Right("done"))
  }

  def handleJsonMsgReq: Route = {
    entity(as[String]) { data =>
      complete {
        handleJsonMsg(data).map[ToResponseMarshallable] {
          case Right(_) => OK
          case e  => handleUnexpectedResponse(e)
        }
      }
    }
  }

  def jsonMsgHandler(implicit req: HttpRequest): Route = {
    req.entity.contentType.mediaType match {
      case MediaTypes.`application/json` =>
        handleJsonMsgReq
      case _ =>
        // non-binary message received
        reject
    }
  }

  val edgeRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("edge") {
        pathPrefix(s"${listeningEndpoint.path}") {
          extractRequest { implicit req: HttpRequest =>
            post {
              jsonMsgHandler
            }
          }
        }
      }
    }

  init()
}
