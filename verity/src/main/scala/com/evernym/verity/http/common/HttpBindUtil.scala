package com.evernym.verity.http.common

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.apphealth.AppStateConstants.CONTEXT_AGENT_SERVICE_INIT
import com.evernym.verity.apphealth.{AppStateManager, ErrorEventParam, SeriousSystemError}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.{HTTP_INTERFACE, HTTP_PORT, HTTP_SSL_PORT}
import com.evernym.verity.Exceptions

import scala.concurrent.Future


trait HttpBindUtil extends HttpsSupport with CorsSupport {

  implicit def system: ActorSystem

  def bindHttpPorts(routes: Route, appConfig: AppConfig): Future[Seq[BindResult]] = {
    val httpBindFuture = try {
      val sbFut = Http().bindAndHandle(corsHandler(routes), appConfig.getConfigStringReq(HTTP_INTERFACE), appConfig.getConfigIntReq(HTTP_PORT))
      sbFut.map(sb => BindResult(s"started listening on port ${appConfig.getConfigIntReq(HTTP_PORT)}", sb))
    } catch {
      case e: Exception =>
        val errorMsg = "unable to bind to http port " +
          s"${appConfig.getConfigIntReq(HTTP_PORT)} (detail => error-msg: ${Exceptions.getErrorMsg(e)})"
        AppStateManager << ErrorEventParam(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg))
        throw e
    }
    val httpsBindFutureOpt = try {
      appConfig.getConfigIntOption(HTTP_SSL_PORT).flatMap { httpsPort =>
        getHttpsConnectionContext.map { https =>
          val sbFut = Http().bindAndHandle(corsHandler(routes), appConfig.getConfigStringReq(HTTP_INTERFACE), httpsPort,
            connectionContext = https)
          sbFut.map(sb => BindResult(s"started listening on port $httpsPort", sb))
        }
      }
    } catch {
      case e: Exception =>
        val errorMsg = "unable to bind to https port " +
          s"${appConfig.getConfigIntOption(HTTP_SSL_PORT).getOrElse("")} (detail => error-msg: ${Exceptions.getErrorMsg(e)})"
        AppStateManager << ErrorEventParam(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg))
        throw e
    }
    Future.sequence(Seq(httpBindFuture) ++ httpsBindFutureOpt.map(f => Seq(f)).getOrElse(Seq.empty))
  }
}

case class BindResult(msg: String, serverBinding: Http.ServerBinding)
