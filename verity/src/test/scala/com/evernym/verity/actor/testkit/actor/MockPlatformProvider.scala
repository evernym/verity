package com.evernym.verity.actor.testkit.actor

import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.actor.{Platform, PlatformServices}
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.appStateManager.{SysServiceNotifier, SysShutdownProvider}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.{MetricsWriter, MetricsWriterExtension, TestMetricsBackend}
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent
import com.evernym.verity.util2.UrlParam
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.typesafe.scalalogging.Logger


class MockPlatform(agentActorContext: AgentActorContext, executionContextProvider: ExecutionContextProvider)
  extends Platform(agentActorContext, MockPlatformServices, executionContextProvider)

object MockPlatformServices extends PlatformServices {
  override def sysServiceNotifier: SysServiceNotifier = MockNotifierService
  override def sysShutdownService: SysShutdownProvider = MockShutdownService
}

object MockNotifierService extends SysServiceNotifier {
  val logger: Logger = Logger("MockNotifierService")
  def setStatus(newStatus: String): Unit = logger.info("status set to : " + newStatus)
  def started(): Unit = logger.info("notified")
  def stop(): Unit = logger.info("stopped")
}

object MockShutdownService extends SysShutdownProvider {
  private val logger = getLoggerByClass(getClass)
  override def performServiceShutdown(): Unit = {
    logger.info("performing mock system exit (as this must be running in test case environment)")
  }
}

trait ProvidesMockPlatform extends MockAppConfig { tc =>

  implicit val system: ActorSystem

  def executionContextProvider: ExecutionContextProvider

  def localAgencyEndpoint: String = "localhost:9000"

  def actorTypeToRegions: Map[Int, ActorRef] = Map.empty
  def mockAgentMsgRouterProvider(): Option[MockAgentMsgRouter] = {
    Option(new MockAgentMsgRouter(executionContextProvider.futureExecutionContext, actorTypeToRegions)(appConfig, system))
  }

  lazy val platform : Platform = {
    val plt = new MockPlatform(new MockAgentActorContext(system, appConfig, executionContextProvider, mockAgentMsgRouterProvider), executionContextProvider)
    MetricsWriterExtension(plt.actorSystem).updateMetricsBackend(testMetricsBackend)
    plt
  }

  lazy val agentActorContext: AgentActorContext = platform.agentActorContext
  lazy val testMetricsBackend: TestMetricsBackend = new TestMetricsBackend
  lazy val metricsWriter: MetricsWriter = platform.agentActorContext.metricsWriter

  lazy val walletAPI: WalletAPI = platform.agentActorContext.walletAPI
  lazy val singletonParentProxy: ActorRef = platform.singletonParentProxy

  lazy val routeRegion: ActorRef = platform.routeRegion

  lazy val agencyAgentRegion: ActorRef = platform.agencyAgentRegion
  lazy val agencyAgentPairwiseRegion : ActorRef = platform.agencyAgentPairwiseRegion

  lazy val userAgentRegionActor: ActorRef = platform.userAgentRegion
  lazy val userAgentPairwiseRegionActor: ActorRef = platform.userAgentPairwiseRegion
  lazy val activityTrackerRegionActor: ActorRef = platform.activityTrackerRegion
  lazy val walletRegionActor: ActorRef = platform.walletActorRegion

  lazy val itemManagerRegionActor: ActorRef = platform.itemManagerRegion
  lazy val itemContainerRegionActor: ActorRef = platform.itemContainerRegion

  lazy val mockAgencyAdmin: MockEdgeAgent =
    new MockEdgeAgent(
      UrlParam(localAgencyEndpoint),
      platform.agentActorContext.appConfig,
      executionContextProvider.futureExecutionContext,
      executionContextProvider.walletFutureExecutionContext
    )

  def getTotalAgentMsgsSentByCloudAgentToRemoteAgent: Int = {
    platform.agentActorContext.msgSendingSvc.asInstanceOf[MockMsgSendingSvc].totalBinaryMsgsSent
  }
}

case class MockPlatformParam(mockAgentActorContextParam: MockAgentActorContextParam=MockAgentActorContextParam())