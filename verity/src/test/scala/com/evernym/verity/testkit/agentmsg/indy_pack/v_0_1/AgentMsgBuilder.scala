package com.evernym.verity.testkit.agentmsg.indy_pack.v_0_1

import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_TYPE_DETAIL_GET_TOKEN
import com.evernym.verity.agentmsg.msgpacker.{FwdRouteMsg, PackMsgParam, PackedMsg}
import com.evernym.verity.agentmsg.tokenizer.GetToken
import com.evernym.verity.protocol.engine.Constants.MFV_1_0
import com.evernym.verity.protocol.engine.{MPV_INDY_PACK, MsgPackVersion}
import com.evernym.verity.testkit.agentmsg.AgentMsgHelper
import com.evernym.verity.testkit.mock.agent.MockAgent
import com.evernym.verity.testkit.util.AgentPackMsgUtil.{preparePackedRequestForAgent, preparePackedRequestForRoutes}
import com.evernym.verity.testkit.util.{AgentPackMsgUtil, TestComMethod}
import com.evernym.verity.util.Util.logger

trait AgentMsgBuilder { this: AgentMsgHelper with MockAgent with AgentMsgHelper =>

  object v_0_1_req {

    implicit val msgPackVersion: MsgPackVersion = MPV_INDY_PACK

    def prepareGetToken(id: String, sponsorId: String, pushId: ComMethodDetail): PackedMsg = {
      logger.debug("Prepare get token msg for agency (MFV 0.1)")
      preparePackedRequestForAgent(buildCoreGetTokenMsg(id, sponsorId, pushId))
    }

    def prepareGetTokenRoute(id: String, sponsorId: String, pushId: ComMethodDetail): PackedMsg = {
      logger.debug("Prepare get token msg for agency (MFV 0.1)")
      val agentPayloadMsgs = buildCoreGetTokenMsg(id, sponsorId, pushId)
      val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(MFV_1_0, agentPayloadMsgs, List(fwdRoute))
    }

    def buildCoreGetTokenMsg(id: String, sponsorId: String, pushId: ComMethodDetail):
    PackMsgParam = {
      val agentMsg = GetToken(MSG_TYPE_DETAIL_GET_TOKEN, id, sponsorId, pushId)
      AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToAgencyAgent)
    }

    def prepareUpdateComMethodMsgForAgent(cm: TestComMethod): PackedMsg = {
      prepareUpdateComMethodMsgForAgentBase(MFV_1_0, cm)
    }
  }

}