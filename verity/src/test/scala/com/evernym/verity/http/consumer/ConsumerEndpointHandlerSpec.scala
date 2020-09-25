package com.evernym.verity.http.consumer

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.protocol.engine.MPV_MSG_PACK
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.mock.agency_admin.MockAgencyAdmin
import com.evernym.verity.testkit.mock.cloud_agent.MockCloudAgentBase
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent
import com.evernym.verity.util.HashAlgorithm.SHA256_trunc4
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.util._


class ConsumerEndpointHandlerSpec
  extends BasicSpecWithIndyCleanup
    with ProvidesMockPlatform
    with EndpointHandlerBaseSpec
    with ConnectionSpec
    with MsgExchangeSpec
    with UpdateConnectionStatusSpec
    with UrlMappingSpec {

  override def testEdgeAgent(): Unit = {
    "Consumer edge 1 (MFV 0.5)" - {
      testAnswerFirstInvitation()
    }

    "Consumer cloud agent (for edge 1)" - {
      testReceivedCredOffer()
    }

    "Consumer edge 1 (MFV 0.5)" - {
      testSendCredRequest()
    }

    "Consumer cloud agent (for edge 1)" - {
      testReceivedCred()
    }

    "Consumer edge 1 (MFV 0.5)" - {
      testAnswerSecondInvitation()
    }

    "Consumer edge 1 (MFV 0.5)" - {
      testUpdateConnectionStatus()
      testAcceptPreviousInvite()

      testUrlMapping()
      testWalletBackupAndRecovery()
    }
  }

  override lazy val agentActorContext: AgentActorContext = platform.agentActorContext
  override lazy val mockAgencyAdmin: MockAgencyAdmin = mockConsumerAgencyAdmin
  override lazy val mockEdgeAgent: MockEdgeAgent = mockConsumerEdgeAgent1
  override lazy val mockNewEdgeAgent: MockEdgeAgent = buildMockConsumerEdgeAgent(mockConsumerAgencyAdmin)
  override lazy val mockOthersCloudAgent: MockCloudAgentBase = mockEntCloudAgent
  override implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPV_MSG_PACK, MTV_1_0, packForAgencyRoute = true)

  lazy val actualLongUrl = "http://actual.long.url/invite?t=394i4i3"
  lazy val shortenedUrl: String = HashUtil.hash(SHA256_trunc4)(actualLongUrl).hex

  def setInviteData(connId: String, mockEdgeAgent: MockEdgeAgent, mockEdgeCloudAgent: MockCloudAgentBase): Unit = {
    "setup invite data in others edge and cloud agent" in {
      mockEdgeAgent.setInviteData(connId, mockEdgeCloudAgent)
      val pcd = mockEdgeAgent.pairwiseConnDetail(connId)
      addAgencyEndpointToLedger(pcd.lastSentInvite.senderAgencyDetail.DID, pcd.lastSentInvite.senderAgencyDetail.endpoint)
    }
  }
}
