package com.evernym.verity.testkit.agentmsg.indy_pack.v_0_1

import com.evernym.verity.agentmsg.tokenizer.SendToken
import com.evernym.verity.did.DidStr
import com.evernym.verity.testkit.agentmsg.AgentMsgHelper
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.testkit.mock.agent.{HasCloudAgent, MockAgent}

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

    def handleSendToken(rmw: PackedMsg, unsealFromDID: DidStr): SendToken = {
      unpackSendToken(rmw, unsealFromDID)
    }

    def unpackSendToken(pmw: PackedMsg, unsealFromDID: DidStr)
    : SendToken = {
      val cm = unpackResp_MPV_1_0(pmw, unsealFromDID).head.convertTo[SendToken]
      logApiCallProgressMsg("send-token: " + cm)
      cm
    }

  }

}
