package com.evernym.verity.protocol.container.actor.agent

import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.agent.user.UserAgentPairwiseSpec_V_0_6
import com.evernym.verity.actor.base.Done
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.testkit.agentmsg
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext

class QuestionAnswerActorSpec
  extends UserAgentPairwiseSpec_V_0_6 {

  implicit val msgPackagingContext: AgentMsgPackagingContext =
    agentmsg.AgentMsgPackagingContext(MPF_INDY_PACK, MTV_1_0, packForAgencyRoute = false)

  createKeySpecs(connId1New)
  sendInviteSpecs(connId1New)
  receivedConnReqAcceptedSpecs(connId1New)

  "A questioner" - {

    "when sent question to an agent" - {
      "should be able to route to proper pairwise connection" in {
        val myPairwiseDIDForConn1 = mockEdgeAgent.pairwiseConnDetail(connId1New)
        val agentMsg = mockEdgeAgent.prepareAskQuestionMsgForAgent(myPairwiseDIDForConn1.myPairwiseDidPair.DID)
        ua ! wrapAsPackedMsgParam(agentMsg)
        expectMsg(Done)
      }
    }
  }
}