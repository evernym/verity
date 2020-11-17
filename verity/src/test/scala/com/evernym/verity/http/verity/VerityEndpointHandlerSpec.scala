package com.evernym.verity.http.verity

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.mock.agency_admin.MockAgencyAdmin
import com.evernym.verity.testkit.mock.cloud_agent.MockCloudAgentBase
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent


class VerityEndpointHandlerSpec
  extends BasicSpecWithIndyCleanup
    with ProvidesMockPlatform
    with EndpointHandlerBaseSpec
    with ConnectionSpec
    with MsgExchangeSpec {

  override def testEdgeAgent(): Unit = {
    "Enterprise edge 1 (MFV 0.5)" - {
      testInvalidInvitations()
    }

    "Consumer cloud agent" - {
      prepareForInviteAnswer()
    }

    "Enterprise cloud agent (for edge 1)" - {
      testReceiveAnswerWithoutInvitation()
    }

    "Enterprise edge 1 (MFV 0.5)" - {
      testSendConnectionRequest()
      sendCredOffer()
    }

    "Enterprise cloud agent (for edge 1)" - {
      testReceivedCredRequest()
    }

    "Enterprise edge 1 (MFV 0.5)" - {
      testSendCredMsg()
    }
  }

  override lazy val agentActorContext: AgentActorContext = platform.agentActorContext
  override lazy val mockEdgeAgent: MockEdgeAgent = mockEntEdgeAgent1
  override lazy val mockAgencyAdmin: MockAgencyAdmin = mockEntAgencyAdmin
  override lazy val mockNewEdgeAgent: MockEdgeAgent = buildMockEnterpriseEdgeAgent(mockAgencyAdmin)
  override lazy val mockOthersCloudAgent: MockCloudAgentBase = mockConsumerCloudAgent
  override implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPF_MSG_PACK, MTV_1_0, packForAgencyRoute = true)

}
