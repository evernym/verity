package com.evernym.verity.actor.testkit.actor

import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import com.evernym.verity.actor.{Platform, PlatformServices}
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.appStateManager.{SysServiceNotifier, SysShutdownProvider}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.observability.metrics.{MetricsWriter, MetricsWriterExtension, TestMetricsBackend}
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

trait ProvidesMockPlatform
  extends MockAppConfig
    with MockActorRegionProvider { tc =>

  implicit val system: ActorSystem

  def executionContextProvider: ExecutionContextProvider

  def localAgencyEndpoint: String = "localhost:9000"

  lazy val platform : Platform = {
    val mockAgentMsgRouter = Option(new MockAgentMsgRouter(executionContextProvider.futureExecutionContext, this)(appConfig, system))
    val plt = new MockPlatform(new MockAgentActorContext(system, appConfig, executionContextProvider, mockAgentMsgRouter), executionContextProvider)
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
      executionContextProvider.futureExecutionContext
    )

  def getTotalAgentMsgsSentByCloudAgentToRemoteAgent: Int = {
    platform.agentActorContext.msgSendingSvc.asInstanceOf[MockMsgSendingSvc].totalBinaryMsgsSent
  }

  case object UserInitiatedShutdown extends CoordinatedShutdown.Reason
  def kickOffUserInitiatedShutdown(): Unit = CoordinatedShutdown(system).run(UserInitiatedShutdown)
}

case class MockPlatformParam(mockAgentActorContextParam: MockAgentActorContextParam=MockAgentActorContextParam())