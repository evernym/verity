package com.evernym.verity

import com.evernym.verity.app_launcher.{HttpServer, PlatformBuilder}
import com.evernym.verity.config.{AppConfigWrapper, CommonConfigValidatorCreator}
import com.evernym.verity.http.management_api.AkkaManagementAPI
import com.evernym.verity.http.route_handlers.HttpRouteHandler
import com.evernym.verity.metrics.MetricsReader

object Main extends App {
  //do config validations (this should be the very first thing to do)
  AppConfigWrapper.init(CommonConfigValidatorCreator.baseValidatorCreators)

  //start prometheus reporter
  //intention behind this is to have 'PrometheusReporter' get loaded and it's configuration is validated as well
  MetricsReader

  //create platform (actor system and region actors etc)
  val platform = PlatformBuilder.build()

  //start akka management server (if enabled, by default it is turned off)
  val akkaManagementAPI = new AkkaManagementAPI(platform.appConfig, platform.actorSystem)
  akkaManagementAPI.startHttpServerIfEnabled()

  //start http server
  val httpServer = new HttpServer(platform, new HttpRouteHandler(platform).endpointRoutes)
  httpServer.start()
}
