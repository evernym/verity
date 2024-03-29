package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.agentmsg.msgfamily.AgentMsgContext
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyVersion, MsgName}
import com.evernym.verity.did.didcomm.v1.messages.MsgType
import com.evernym.verity.protocol.engine.MsgBase
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.protocols.connecting.common.ProtoMsg

case class AgentMsgWrapper(msgPackFormat: MsgPackFormat, agentBundledMsg: AgentBundledMsg)
  extends MsgBase
    with ProtoMsg { // this is an artifact of how connection 0.5 and 0.6 work, they need this class to have this
  // marker trait

  def senderVerKey: Option[VerKeyStr] = agentBundledMsg.senderVerKey
  def recipVerKey: Option[VerKeyStr] = agentBundledMsg.recipVerKey

  def headAgentMsg: AgentMsg = agentBundledMsg.headAgentMsg
  def headAgentMsgDetail: MsgFamilyDetail = headAgentMsg.msgFamilyDetail
  def headAgentMsgType: MsgType = headAgentMsgDetail.msgType

  def tailAgentMsgs: List[AgentMsg] = agentBundledMsg.tailAgentMsgs

  def usesLegacyGenMsgWrapper: Boolean = agentBundledMsg.usesLegacyGenMsgWrapper
  def usesLegacyBundledMsgWrapper: Boolean = agentBundledMsg.usesLegacyBundledMsgWrapper

  def isMatched(expectedMsgFamilyVersion: MsgFamilyVersion, expectedMsgName: MsgName): Boolean = {
    if (
      headAgentMsgDetail.msgVer.forall(_ == MTV_1_0) &&  //TODO: this condition is only till we support MFV_0_5 family messages
        headAgentMsgDetail.familyVersion == expectedMsgFamilyVersion &&
        headAgentMsgDetail.msgName == expectedMsgName) true
    else false
  }

  def isMatched(expectedMsgFamilyName: MsgFamilyName, expectedMsgFamilyVersion: MsgFamilyVersion, expectedMsgName: MsgName): Boolean = {
    if (
      headAgentMsgDetail.familyName == expectedMsgFamilyName &&
        headAgentMsgDetail.familyVersion == expectedMsgFamilyVersion &&
        headAgentMsgDetail.msgName == expectedMsgName) true
    else false
  }

  def getAgentMsgContext: AgentMsgContext = AgentMsgContext(msgPackFormat, headAgentMsgDetail.familyVersion, senderVerKey)

  def msgType: MsgType = headAgentMsgType

}

object AgentMessageWrapper {

  def apply(jsonString: String,
            msgPackFormat: MsgPackFormat,
            senderVerKeyOpt: Option[VerKeyStr]=None): AgentMsgWrapper  = {
    val agentMsg = AgentMsgParseUtil.agentMsg(jsonString)
    val agentMsgs = List(agentMsg)
    AgentMsgWrapper(msgPackFormat, AgentBundledMsg(agentMsgs, senderVerKeyOpt, None, None))
  }

}