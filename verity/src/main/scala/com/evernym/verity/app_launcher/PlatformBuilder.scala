package com.evernym.verity.app_launcher

import akka.actor.ActorSystem
import com.evernym.verity.actor.{Platform, PlatformServices}
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.ExecutionContext


object PlatformBuilder {
  def build(executionContextProvider: ExecutionContextProvider, appConfig: AppConfig, agentActorContext: Option[AgentActorContext]=None): Platform = {
    new Platform(agentActorContext.getOrElse(new DefaultAgentActorContext(executionContextProvider, appConfig)), PlatformServices, executionContextProvider)
  }
}

class DefaultAgentActorContext(val executionContextProvider: ExecutionContextProvider, val appConfig: AppConfig) extends AgentActorContext {
  override implicit lazy val system: ActorSystem = createActorSystem()

  /**
   * custom thread pool executor
   */
  override def futureWalletExecutionContext: ExecutionContext = executionContextProvider.walletFutureExecutionContext

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContextProvider.futureExecutionContext
}