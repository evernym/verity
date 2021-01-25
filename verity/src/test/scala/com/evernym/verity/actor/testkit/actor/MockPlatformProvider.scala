package com.evernym.verity.actor.testkit.actor

import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.testkit.mock.agency_admin.MockAgencyAdmin
import com.evernym.verity.UrlParam
import com.evernym.verity.vault.service.WalletService
import com.evernym.verity.vault.wallet_api.WalletAPI


class MockPlatform(agentActorContext: AgentActorContext)
  extends Platform(agentActorContext)

trait ProvidesMockPlatform extends MockAppConfig { tc =>

  implicit val system: ActorSystem

  def localAgencyEndpoint: String = "localhost:9000"

  lazy val platform: Platform = new MockPlatform(new MockAgentActorContext(system, appConfig, mockAgentActorContextParam))
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

  lazy val mockAgencyAdmin: MockAgencyAdmin =
    new MockAgencyAdmin(system, UrlParam(localAgencyEndpoint), platform.agentActorContext.appConfig)

  def getTotalAgentMsgsSentByCloudAgentToRemoteAgent: Int = {
    platform.agentActorContext.msgSendingSvc.asInstanceOf[MockMsgSendingSvc].totalAgentMsgsSent
  }

  lazy val mockRouteStoreActorTypeToRegions: Map[Int, ActorRef] = Map.empty
  lazy val mockAgentActorContextParam: MockAgentActorContextParam = MockAgentActorContextParam(mockRouteStoreActorTypeToRegions)
}

case class MockPlatformParam(mockAgentActorContextParam: MockAgentActorContextParam=MockAgentActorContextParam())