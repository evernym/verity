package com.evernym.verity.http.common

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.{HTTP_INTERFACE, HTTP_PORT}
import com.evernym.verity.actor.appStateManager.{AppStateUpdateAPI, ErrorEvent, SeriousSystemError}
import com.evernym.verity.util2.Exceptions

import scala.concurrent.Future


trait HttpServerUtil extends CorsSupport {

  implicit def system: ActorSystem

  protected def startNewServer(routes: Route, appConfig: AppConfig): Future[Seq[HttpServerBindResult]] = {
    val httpBindFuture = try {
      val sbFut = Http().newServerAt(appConfig.getStringReq(HTTP_INTERFACE), appConfig.getIntReq(HTTP_PORT)).bind(corsHandler(routes))
      sbFut.map(sb => HttpServerBindResult(s"started listening on port ${appConfig.getIntReq(HTTP_PORT)}", sb))
    } catch {
      case e: Exception =>
        val errorMsg = "unable to bind to http port " +
          s"${appConfig.getIntReq(HTTP_PORT)} (detail => error-msg: ${Exceptions.getErrorMsg(e)})"
        AppStateUpdateAPI(system).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg)))
        throw e
    }
    Future.sequence(Seq(httpBindFuture))
  }
}

case class HttpServerBindResult(msg: String, serverBinding: Http.ServerBinding)
