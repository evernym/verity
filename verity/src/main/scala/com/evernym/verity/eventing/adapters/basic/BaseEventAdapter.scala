package com.evernym.verity.eventing.adapters.basic

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.GatewayTimeout
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.util.Timeout
import com.evernym.verity.config.AppConfig
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


trait BaseEventAdapter {

  def appConfig: AppConfig
  implicit val timeout: Timeout = Timeout(25.seconds)
  lazy val logger: Logger = getLoggerByClass(getClass)

  protected def postHttpRequest(request: HttpRequest)
                               (implicit system: ActorSystem, executionContext: ExecutionContext): Future[HttpResponse] = {
    Http().singleRequest(request).recover {
      case e =>
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        val errMsg = s"connection not established with event store server: ${request.uri}"
        logger.error(errMsg)
        HttpResponse(StatusCodes.custom(GatewayTimeout.intValue, errMsg, errMsg))
    }
  }

  protected val eventStoreParam: HttpServerParam = {
    HttpServerParam(
      appConfig.getStringReq("verity.eventing.basic-store.http-listener.host"),
      appConfig.getIntReq("verity.eventing.basic-store.http-listener.port")
    )
  }

}