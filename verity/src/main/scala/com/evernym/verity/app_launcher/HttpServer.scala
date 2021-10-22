package com.evernym.verity.app_launcher

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.actor.appStateManager._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.{HttpServerBindResult, HttpServerUtil}
import com.evernym.verity.observability.metrics.CustomMetrics.{AS_START_TIME, initGaugeMetrics}
import com.evernym.verity.observability.logs.LoggingUtil
import com.evernym.verity.observability.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.util.UnableToCreateLogger
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

class HttpServer(val platform: Platform, routes: Route, executionContext: ExecutionContext)
  extends HttpServerUtil
  with HasExecutionContextProvider {

  override def futureExecutionContext: ExecutionContext = executionContext

  val logger: Logger = LoggingUtil.getLoggerByClass(classOf[HttpServer])
  implicit lazy val appConfig: AppConfig = platform.agentActorContext.appConfig
  implicit lazy val system: ActorSystem = platform.agentActorContext.system
  lazy implicit val executor: ExecutionContextExecutor = system.dispatcher

  lazy val metricsWriter : MetricsWriter = platform.agentActorContext.metricsWriter

  var httpBinding: Option[ServerBinding] = None

  def start(): Unit = {
    LaunchPreCheck.waitForRequiredDepsIsOk(platform.healthChecker, platform.executionContextProvider.futureExecutionContext)
    startService(init _)
  }

  def stop(): Future[Done] = {
    httpBinding match {
      case Some(hb) => hb.terminate(Duration(30, SECONDS)).map(_ => Done)
      case None => Future(Done)
    }
  }

  private def init(): (Future[Seq[HttpServerBindResult]]) = {
    startNewServer(routes, appConfig)
  }

  private def startService(f: () => Future[Seq[HttpServerBindResult]]): Unit = {
    try {
      val serviceStartTime = LocalDateTime.now
      val bindResultFut = f()
      bindResultFut.onComplete {
        case Success(bindResults) =>
          bindResults.foreach { br =>
            httpBinding = Option(br.serverBinding)
            AppStateUpdateAPI(system).publishEvent(SuccessEvent(ListeningSuccessful, CONTEXT_AGENT_SERVICE_INIT,
              causeDetail = CauseDetail("agent-service-started", "agent-service-started-listening-successfully"),
              msg = Option(br.msg)))
          }
          val serviceStartFinishTime = LocalDateTime.now
          val millis = ChronoUnit.MILLIS.between(serviceStartTime, serviceStartFinishTime)
          metricsWriter.gaugeUpdate(AS_START_TIME, millis)
          initGaugeMetrics(metricsWriter)
        case Failure(e) =>
          throw e
      }
    } catch {
      case e: UnableToCreateLogger =>
        AppStateUpdateAPI(system).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_GENERAL, e, Option(e.msg)))
        System.err.println(e.msg)
        System.exit(1)
      case e: Exception =>
        val errorMsg = s"Unable to start agent service: ${Exceptions.getErrorMsg(e)}"
        AppStateUpdateAPI(system).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg)))
        System.err.println(errorMsg)
        System.exit(1)
    }
  }
}
