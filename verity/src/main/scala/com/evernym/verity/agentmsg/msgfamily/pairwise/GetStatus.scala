package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.agentmsg.msgfamily.LegacyMsgBase
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.validate.ValidateHelper.checkRequired

case class GetStatusReqMsg_MFV_0_6(`@type`: String, sourceId: String) extends LegacyMsgBase with Control {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("sourceId", sourceId)
  }
}

object GetStatusMsgHelper {

  def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): GetStatusReqMsg_MFV_0_6 = {
    amw.headAgentMsg.convertTo[GetStatusReqMsg_MFV_0_6]
  }

}