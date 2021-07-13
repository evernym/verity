package com.evernym.verity.http.consumer

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.mock.agent.{MockCloudAgent, MockEdgeAgent, MockEnvUtil}
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
      testAnswerFirstInvitation(mockEntEdgeEnv)
    }

    "Consumer cloud agent (for edge 1)" - {
      testReceivedCredOffer(mockEntEdgeEnv)
    }

    "Consumer edge 1 (MFV 0.5)" - {
      testSendCredRequest(mockEntEdgeEnv)
    }

    "Consumer cloud agent (for edge 1)" - {
      testReceivedCred(mockEntEdgeEnv)
    }

    "Consumer edge 1 (MFV 0.5)" - {
      testAnswerSecondInvitation(mockEntEdgeEnv)
    }

    "Consumer edge 1 (MFV 0.5)" - {
      testUpdateConnectionStatus(mockEntEdgeEnv)
      testAcceptPreviousInvite(mockEntEdgeEnv)

      testUrlMapping()
      testWalletBackupAndRecovery(mockEntEdgeEnv.edgeAgent, mockNewEdgeAgent)
    }
  }

  override lazy val agentActorContext: AgentActorContext = platform.agentActorContext
  lazy val mockNewEdgeAgent: MockEdgeAgent = MockEnvUtil.buildMockEdgeAgent(mockEntEdgeEnv.agencyEdgeAgent)
  override implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPF_MSG_PACK, MTV_1_0, packForAgencyRoute = true)

  lazy val actualLongUrl = "http://actual.long.url/invite?t=394i4i3"
  lazy val shortenedUrl: String = HashUtil.hash(SHA256_trunc4)(actualLongUrl).hex

  def setInviteData(mockEdgeAgent: MockEdgeAgent,
                    mockEdgeCloudAgent: MockCloudAgent,
                    connId: String): Unit = {
    "setup invite data in others edge and cloud agent" in {
      mockEdgeAgent.setInviteData(connId, mockEdgeCloudAgent)
      val pcd = mockEdgeAgent.pairwiseConnDetail(connId)
      addAgencyEndpointToLedger(pcd.lastSentInvite.senderAgencyDetail.DID, pcd.lastSentInvite.senderAgencyDetail.endpoint)
    }
  }
}
