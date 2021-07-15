package com.evernym.verity

import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import com.evernym.verity.app_launcher.{HttpServer, PlatformBuilder}
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.ConfigConstants.{AKKA_MNGMNT_CLUSTER_BOOTSTRAP_ENABLED, AKKA_MNGMNT_HTTP_ENABLED}
import com.evernym.verity.http.route_handlers.HttpRouteHandler
import com.evernym.verity.util2.ExecutionContextProvider


object Main extends App {
  //do config validations (this should be the very first thing to do)
  AppConfigWrapper.init()

  val ecp: ExecutionContextProvider = new ExecutionContextProvider(AppConfigWrapper)

  //create platform (actor system and region actors etc)
  val platform = PlatformBuilder.build(ecp)

  //start akka management server (if enabled, by default it is turned off)
  platform.startExtensionIfEnabled(AkkaManagement, AKKA_MNGMNT_HTTP_ENABLED)(_.start())

  //start akka management bootstrap (if enabled, by default it is turned off)
  platform.startExtensionIfEnabled(ClusterBootstrap, AKKA_MNGMNT_CLUSTER_BOOTSTRAP_ENABLED)(_.start())

  //start akka http server
  val httpServer = new HttpServer(
    platform,
    new HttpRouteHandler(platform, ecp.futureExecutionContext).endpointRoutes,
    ecp.futureExecutionContext
  )
  httpServer.start()
}
