package com.evernym.verity.testkit.agentmsg.indy_pack.v_0_1

import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.agentmsg.tokenizer.SendToken
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.testkit.agentmsg.AgentMsgHelper
import com.evernym.verity.testkit.mock.HasCloudAgent
import com.evernym.verity.testkit.mock.agent.MockAgent

/**
 * this will handle received/incoming/response agent messages
 */
trait AgentMsgHandler { this: AgentMsgHelper with MockAgent with HasCloudAgent =>

  object v_0_1_resp {

    def handleSendTokenResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): SendToken = {
      logApiCallProgressMsg("Unpacking send token response")
      val acm = unpackSendToken(rmw, getDIDToUnsealAgentRespMsg)
      acm
    }

    def handleSendToken(rmw: PackedMsg, unsealFromDID: DID): SendToken = {
      unpackSendToken(rmw, unsealFromDID)
    }

    def unpackSendToken(pmw: PackedMsg, unsealFromDID: DID)
    : SendToken = {
      val cm = unpackResp_MPV_1_0(pmw, unsealFromDID).head.convertTo[SendToken]
      logApiCallProgressMsg("send-token: " + cm)
      cm
    }

  }

}
