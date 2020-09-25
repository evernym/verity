package com.evernym.verity.app_launcher

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.apphealth.AppStateConstants.{CONTEXT_AGENT_SERVICE_INIT, CONTEXT_GENERAL}
import com.evernym.verity.apphealth._
import com.evernym.verity.config.{AppConfig, AppConfigWrapper, CommonConfigValidatorCreator}
import com.evernym.verity.http.common.{BindResult, HttpBindUtil}
import com.evernym.verity.http.management_api.AkkaManagementAPI
import com.evernym.verity.http.route_handlers.EndpointHandlerBase
import com.evernym.verity.metrics.CustomMetrics.{AS_START_TIME, initGaugeMetrics}
import com.evernym.verity.metrics.{MetricsReader, MetricsWriter}
import com.evernym.verity.protocol.engine.util.UnableToCreateLogger
import com.evernym.verity.Exceptions
import sun.misc.{Signal, SignalHandler}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}


trait PlatformLauncher
  extends HttpBindUtil
    with EndpointHandlerBase
    with AkkaManagementAPI
    with AgentActorContext
    with App {

  def start(): Unit = {
    startService(init _)
  }

  override implicit lazy val appConfig: AppConfig = platform.agentActorContext.appConfig
  override implicit lazy val system: ActorSystem = platform.agentActorContext.system
  lazy implicit val executor: ExecutionContextExecutor = system.dispatcher

  def endpointRoutes: Route

  class DefaultAgentActorContext extends AgentActorContext {
    override implicit lazy val appConfig: AppConfig = AppConfigWrapper
    override implicit lazy val system: ActorSystem = createActorSystem()
  }

  def agentActorContextProvider: AgentActorContext = new DefaultAgentActorContext()

  lazy val platform: Platform = {
    val p = new Platform(agentActorContextProvider)
    LaunchPreCheck.checkReqDependencies(p.agentActorContext)
    p
  }

  private def startHttpBinding(): Future[Seq[BindResult]] = {
    bindHttpPorts(endpointRoutes, appConfig)
  }

  private def init(): (ActorSystem, Future[Seq[BindResult]]) = {
    AppConfigWrapper.init(CommonConfigValidatorCreator.baseValidatorCreators)
    startAkkaManagementHttpApiListenerIfEnabled()
    val bindResult = startHttpBinding()
    MetricsReader   //intention behind this is to have 'PrometheusReporter' get loaded and it's configuration is checked as well
    (platform.actorSystem, bindResult)
  }


  protected def startService(f:() => (ActorSystem, Future[Seq[BindResult]])): Unit = {
    try {
      val serviceStartTime = LocalDateTime.now
      val (actorSystem, bindResultFut) = f()
      bindResultFut.onComplete {
        case Success(bindResults) =>
          // Drain the Akka node on a SIGTERM - systemd sends the JVM a SIGTERM on a 'systemctl stop'
          Signal.handle(new Signal("TERM"), new SignalHandler() {
            def handle(sig: Signal): Unit = {
              logger.info("Trapping SIGTERM and begin draining Akka node...")
              AppStateManager.drain(actorSystem)
            }
          })
          bindResults.foreach { br =>
            AppStateManager << SuccessEventParam(ListeningSuccessful, CONTEXT_AGENT_SERVICE_INIT,
              causeDetail = CauseDetail("agent-service-started", "agent-service-started-listening-successfully"),
              msg = Option(br.msg))
          }
          val serviceStartFinishTime = LocalDateTime.now
          val millis = ChronoUnit.MILLIS.between(serviceStartTime, serviceStartFinishTime)
          MetricsWriter.gaugeApi.updateWithTags(AS_START_TIME, millis)
          initGaugeMetrics()
        case Failure(e) =>
          throw e
      }
    } catch {
      case e: UnableToCreateLogger =>
        AppStateManager << ErrorEventParam(SeriousSystemError, CONTEXT_GENERAL, e, Option(e.msg))
        System.err.println(e.msg)
        System.exit(1)
      case e: Exception =>
        val errorMsg = s"Unable to start agent service: ${Exceptions.getErrorMsg(e)}"
        AppStateManager << ErrorEventParam(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg))
        System.err.println(errorMsg)
        System.exit(1)
    }
  }
}
