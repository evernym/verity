package com.evernym.verity.actor.agent.agency.agent_provisioning

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute, RoutingAgentUtil}
import com.evernym.verity.actor.testkit.{AgentSpecHelper, PersistentActorSpec}
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.mock.agency_admin.MockAgencyAdmin
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.UrlDetail
import com.evernym.verity.vault.wallet_api.WalletAPI
import org.scalatest.concurrent.Eventually


trait AgencyAgentPairwiseSpecBase
  extends BasicSpec
    with PersistentActorSpec
    with AgentSpecHelper
    with Eventually {

  val aac: AgentActorContext = platform.agentActorContext

  implicit lazy val wap: WalletAPIParam = WalletAPIParam(agencyAgentEntityId)

  val agencyAgentWalletAPI: WalletAPI = platform.agentActorContext.walletAPI
  var agencyAgentPairwiseDID:DID = _

  override lazy val mockAgencyAdmin: MockAgencyAdmin =
    new MockAgencyAdmin(system, UrlDetail("localhost:9001"), platform.agentActorContext.appConfig)

  override lazy val mockEdgeAgent: MockEdgeAgent = buildMockConsumerEdgeAgent(platform.agentActorContext.appConfig, mockAgencyAdmin)

  def setPairwiseEntityId(agentPairwiseDID: DID): Unit = {
    agencyAgentPairwiseDID = agentPairwiseDID
    val bucketId = RoutingAgentUtil.getBucketEntityId(agentPairwiseDID)
    agentRouteStoreRegion ! ForIdentifier(bucketId, GetRoute(agentPairwiseDID))
    val addressDetail = expectMsgType[Option[ActorAddressDetail]]
    addressDetail.isDefined shouldBe true
    agencyAgentPairwiseEntityId = addressDetail.get.address
  }
}
