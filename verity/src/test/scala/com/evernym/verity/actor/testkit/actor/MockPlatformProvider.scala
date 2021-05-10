package com.evernym.verity.actor.testkit.actor

import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.actor.{Platform, PlatformServices}
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.UrlParam
import com.evernym.verity.actor.appStateManager.{SysServiceNotifier, SysShutdownProvider}
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent
import com.evernym.verity.testkit.util.TestUtil.logger
import com.evernym.verity.vault.service.WalletService
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.typesafe.scalalogging.Logger


class MockPlatform(agentActorContext: AgentActorContext)
  extends Platform(agentActorContext, MockPlatformServices)

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
  override def performServiceShutdown(): Unit = {
    logger.info("performing mock system exit (as this must be running in test case environment)")
  }
}

trait ProvidesMockPlatform extends MockAppConfig { tc =>

  implicit val system: ActorSystem

  def localAgencyEndpoint: String = "localhost:9000"

  def actorTypeToRegions: Map[Int, ActorRef] = Map.empty
  def mockAgentMsgRouterProvider(): Option[MockAgentMsgRouter] = {
    Option(new MockAgentMsgRouter(actorTypeToRegions)(appConfig, system))
  }

  lazy val platform: Platform = new MockPlatform(new MockAgentActorContext(system, appConfig, mockAgentMsgRouterProvider))
  lazy val agentActorContext: AgentActorContext = platform.agentActorContext

  lazy val walletService: WalletService = platform.agentActorContext.walletService
  lazy val walletAPI: WalletAPI = platform.agentActorContext.walletAPI

  lazy val singletonParentProxy: ActorRef = platform.singletonParentProxy

  lazy val agentRouteStoreRegion: ActorRef = platform.agentRouteStoreRegion

  lazy val agencyAgentRegion: ActorRef = platform.agencyAgentRegion
  lazy val agencyAgentPairwiseRegion : ActorRef = platform.agencyAgentPairwiseRegion

  lazy val userAgentRegionActor: ActorRef = platform.userAgentRegion
  lazy val userAgentPairwiseRegionActor: ActorRef = platform.userAgentPairwiseRegion
  lazy val activityTrackerRegionActor: ActorRef = platform.activityTrackerRegion
  lazy val walletRegionActor: ActorRef = platform.walletActorRegion

  lazy val itemManagerRegionActor: ActorRef = platform.itemManagerRegion
  lazy val itemContainerRegionActor: ActorRef = platform.itemContainerRegion

  lazy val mockAgencyAdmin: MockEdgeAgent =
    new MockEdgeAgent(UrlParam(localAgencyEndpoint), platform.agentActorContext.appConfig)

  def getTotalAgentMsgsSentByCloudAgentToRemoteAgent: Int = {
    platform.agentActorContext.msgSendingSvc.asInstanceOf[MockMsgSendingSvc].totalBinaryMsgsSent
  }
}

case class MockPlatformParam(mockAgentActorContextParam: MockAgentActorContextParam=MockAgentActorContextParam())