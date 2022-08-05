package com.evernym.verity.http.verity

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.constants.Constants._
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext

import scala.concurrent.ExecutionContext


class VerityEndpointHandlerSpec
  extends BasicSpecWithIndyCleanup
    with ProvidesMockPlatform
    with EndpointHandlerBaseSpec
    with ConnectionSpec
    with MsgExchangeSpec {

  override def testEdgeAgent(): Unit = {
    "Enterprise edge 1 (MFV 0.5)" - {
      testInvalidInvitations(mockEntEdgeEnv)
    }

    "Consumer cloud agent" - {
      prepareForInviteAnswer(mockUserEdgeEnv)
    }

    "Enterprise cloud agent (for edge 1)" - {
      testReceiveAnswerWithoutInvitation(mockEntEdgeEnv)
    }

    "Enterprise edge 1 (MFV 0.5)" - {
      testSendConnectionRequest(mockEntEdgeEnv)
      sendCredOffer(mockEntEdgeEnv)
    }

    "Enterprise cloud agent (for edge 1)" - {
      testReceivedCredRequest(mockEntEdgeEnv)
    }

    "Enterprise edge 1 (MFV 0.5)" - {
      testSendCredMsg(mockEntEdgeEnv)
    }
  }

  override lazy val agentActorContext: AgentActorContext = platform.agentActorContext

  override implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPF_MSG_PACK, MTV_1_0, packForAgencyRoute = true)
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)

  override def executionContextProvider: ExecutionContextProvider = ecp

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}
