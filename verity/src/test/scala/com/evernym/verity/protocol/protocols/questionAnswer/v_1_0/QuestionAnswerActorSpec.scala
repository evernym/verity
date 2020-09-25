package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0

import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.user.UserAgentPairwiseSpec_V_0_6
import com.evernym.verity.actor.persistence.Done
import com.evernym.verity.actor.testkit.{AgentSpecHelper, PersistentActorSpec}
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.protocol.engine.MPV_INDY_PACK
import com.evernym.verity.testkit.agentmsg
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext

class QuestionAnswerActorSpec
  extends UserAgentPairwiseSpec_V_0_6 {

  implicit val msgPackagingContext: AgentMsgPackagingContext =
    agentmsg.AgentMsgPackagingContext(MPV_INDY_PACK, MTV_1_0, packForAgencyRoute = false)

  createKeySpecs(connId1New)
  sendInviteSpecs(connId1New)
  receivedConnReqAcceptedSpecs(connId1New)

  "A questioner" - {

    "when sent question to a agent" - {
      "should be able to route to proper pairwise connection" in {
        val myPairwiseDIDForConn1 = mockEdgeAgent.pairwiseConnDetail(connId1New)
        val agentMsg = mockEdgeAgent.prepareAskQuestionMsgForAgent(myPairwiseDIDForConn1.myPairwiseDidPair.DID)
        ua ! wrapAsPackedMsgParam(agentMsg)
        expectMsg(Done)
      }
    }
  }
}