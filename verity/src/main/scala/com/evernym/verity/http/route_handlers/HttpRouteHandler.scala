package com.evernym.verity.http.route_handlers

import akka.actor.ActorSystem
import com.evernym.verity.actor.{AppStateCoordinator, Platform}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.observability.metrics.MetricsWriter
import com.evernym.verity.util.healthcheck.HealthChecker

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class HttpRouteHandler(val platform: Platform, executionContext: ExecutionContext)
  extends HttpRoutes {

  override def system: ActorSystem = platform.actorSystem

  override def appConfig: AppConfig = platform.appConfig

  override implicit def executor: ExecutionContextExecutor = system.dispatcher

  override val metricsWriter: MetricsWriter = platform.agentActorContext.metricsWriter
  override val healthChecker: HealthChecker = platform.healthChecker
  override val appStateCoordinator: AppStateCoordinator = platform.appStateCoordinator

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}
