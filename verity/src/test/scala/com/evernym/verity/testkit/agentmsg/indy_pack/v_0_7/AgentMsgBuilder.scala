package com.evernym.verity.testkit.agentmsg.indy_pack.v_0_7

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.agentmsg.msgpacker.{FwdRouteMsg, PackMsgParam, PackedMsg}
import com.evernym.verity.protocol.engine.Constants.{MFV_0_7, MFV_1_0, MSG_FAMILY_AGENT_PROVISIONING, MSG_TYPE_CREATE_AGENT}
import com.evernym.verity.protocol.engine.MsgFamily.{EVERNYM_QUALIFIER, typeStrFromMsgType}
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{ProvisionToken, RequesterKeys}
import com.evernym.verity.testkit.agentmsg.AgentMsgHelper
import com.evernym.verity.testkit.mock.agent.MockAgent
import com.evernym.verity.testkit.util.AgentPackMsgUtil._
import com.evernym.verity.testkit.util.{AgentPackMsgUtil, CreateAgent_MFV_0_7, CreateEdgeAgent_MFV_0_7}
import com.evernym.verity.util.Util.logger

import scala.util.Left

trait AgentMsgBuilder { this: AgentMsgHelper with MockAgent with AgentMsgHelper =>

  object v_0_7_req {

    implicit val msgPackFormat: MsgPackFormat = MPF_INDY_PACK

    def prepareCreateAgentMsgForAgency(forDID: DID, requesterKeys: RequesterKeys, token: Option[ProvisionToken]): PackedMsg = {
      logger.debug("Prepare create agent msg for agency (MFV 0.7)")
      val agentPayloadMsgs = buildCoreCreateAgentMsg(forDID, requesterKeys, token)
      val fwdRoute = FwdRouteMsg(forDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(MFV_1_0, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareCreateAgentMsg(forDID: DID, requesterKeys: RequesterKeys, requesterDetails: Option[ProvisionToken])
                                     : PackedMsg = {
      preparePackedRequestForAgent(buildCoreCreateAgentMsg(forDID, requesterKeys, requesterDetails))
    }

    def buildCoreCreateAgentMsg(forDID: DID, requesterKeys: RequesterKeys,
                                        requesterDetails: Option[ProvisionToken]):
    PackMsgParam = {
      val agentMsg = CreateAgent_MFV_0_7(MSG_TYPE_DETAIL_CREATE_AGENT_0_7, requesterKeys, requesterDetails)
      AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToGivenDID(forDID))
    }

    def prepareCreateEdgeAgentMsg(forDID: DID, requesterKeys: RequesterKeys, requesterDetails: Option[ProvisionToken])
    : PackedMsg = {
      preparePackedRequestForAgent(buildCoreCreateEdgeAgentMsg(forDID, requesterKeys.fromVerKey, requesterDetails))
    }

    def buildCoreCreateEdgeAgentMsg(forDID: DID, requesterVk: VerKey, requesterDetails: Option[ProvisionToken]):
    PackMsgParam = {
      val agentMsg = CreateEdgeAgent_MFV_0_7(MSG_TYPE_DETAIL_CREATE_EDGE_AGENT_0_7, requesterVk, requesterDetails)
      AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToGivenDID(forDID))
    }
  }

  val MSG_TYPE_DETAIL_CREATE_AGENT_0_7: String = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_AGENT_PROVISIONING, MFV_0_7, MSG_TYPE_CREATE_AGENT)
  val MSG_TYPE_DETAIL_CREATE_EDGE_AGENT_0_7: String = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_AGENT_PROVISIONING, MFV_0_7, "create-edge-agent")
}