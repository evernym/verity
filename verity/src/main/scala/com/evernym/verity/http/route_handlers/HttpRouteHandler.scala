package com.evernym.verity.http.route_handlers

import akka.actor.ActorSystem
import com.evernym.verity.actor.Platform
import com.evernym.verity.config.AppConfig
import com.evernym.verity.metrics.{MetricsWriter, MetricsWriterExtension}

import scala.concurrent.ExecutionContextExecutor

class HttpRouteHandler(val platform: Platform)
  extends EndpointHandlerBase {

  override def system: ActorSystem = platform.actorSystem
  override def appConfig: AppConfig = platform.appConfig
  override implicit def executor: ExecutionContextExecutor = system.dispatcher
  override val metricsWriter: MetricsWriter = MetricsWriterExtension(system).get()
}
