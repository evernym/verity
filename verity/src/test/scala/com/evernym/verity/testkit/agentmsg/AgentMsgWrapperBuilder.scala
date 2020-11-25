package com.evernym.verity.testkit.agentmsg

import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_TYPE_DETAIL_CREATE_AGENT
import com.evernym.verity.agentmsg.msgpacker.{AgentMessageWrapper, AgentMsgWrapper}
import com.evernym.verity.protocol.actor.ProtoMsg
import com.evernym.verity.protocol.engine.{DID, VerKey}

trait AgentMsgWrapperBuilder extends CommonSpecUtil {

  case class CreateAgentReqMsg_MFV_0_6(`@type`: String, fromDID: String, fromDIDVerKey: String) extends ProtoMsg

  def buildCreateAgentMsgWrapper_MFV_0_6: AgentMsgWrapper = {
    val newDID = generateNewDid()
    buildCreateAgentMsgWrapper_MFV_0_6(newDID.DID, newDID.verKey)
  }

  def buildCreateAgentMsgWrapper_MFV_0_6(fromDID: DID, fromDIDVerKey: VerKey): AgentMsgWrapper = {
    val carm = CreateAgentReqMsg_MFV_0_6(MSG_TYPE_DETAIL_CREATE_AGENT, fromDID: DID, fromDIDVerKey: VerKey)
    AgentMessageWrapper(DefaultMsgCodec.toJson(carm), MPF_INDY_PACK)
  }
}

object AgentMsgWrapperBuilder extends AgentMsgWrapperBuilder
