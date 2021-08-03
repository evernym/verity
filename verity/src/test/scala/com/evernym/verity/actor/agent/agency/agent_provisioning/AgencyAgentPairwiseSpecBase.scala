package com.evernym.verity.actor.agent.agency.agent_provisioning

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetStoredRoute}
import com.evernym.verity.actor.testkit.{AgentSpecHelper, PersistentActorSpec}
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.did.DidStr
import com.evernym.verity.testkit.mock.agent.MockEnvUtil._
import com.evernym.verity.testkit.{BasicSpec, HasTestWalletAPI}
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent
import com.evernym.verity.util2.UrlParam
import org.scalatest.concurrent.Eventually


trait AgencyAgentPairwiseSpecBase
  extends BasicSpec
    with PersistentActorSpec
    with AgentSpecHelper
    with Eventually
    with HasTestWalletAPI {

  val aac: AgentActorContext = platform.agentActorContext

  lazy val agencyWalletParam: WalletAPIParam = WalletAPIParam(agencyAgentEntityId)

  var agencyAgentPairwiseDID:DidStr = _

  override lazy val mockAgencyAdmin: MockEdgeAgent =
    new MockEdgeAgent(UrlParam("localhost:9001"), platform.agentActorContext.appConfig)

  override lazy val mockEdgeAgent: MockEdgeAgent = buildMockEdgeAgent(mockAgencyAdmin)

  def setPairwiseEntityId(agentPairwiseDID: DidStr): Unit = {
    agencyAgentPairwiseDID = agentPairwiseDID
    routeRegion ! ForIdentifier(agentPairwiseDID, GetStoredRoute)
    val addressDetail = expectMsgType[Option[ActorAddressDetail]]
    addressDetail.isDefined shouldBe true
    agencyAgentPairwiseEntityId = addressDetail.get.address
  }
}
