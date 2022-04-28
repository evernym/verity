package com.evernym.verity.http.route_handlers

import akka.actor.ActorSystem
import com.evernym.verity.actor.Platform
import com.evernym.verity.config.AppConfig
import com.evernym.verity.observability.metrics.MetricsWriter

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class HttpRouteHandler(val platform: Platform, executionContext: ExecutionContext)
  extends EndpointHandlerBase {

  override def system: ActorSystem = platform.actorSystem

  override def appConfig: AppConfig = platform.appConfig

  override implicit def executor: ExecutionContextExecutor = system.dispatcher

  override val metricsWriter: MetricsWriter = platform.agentActorContext.metricsWriter

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}
