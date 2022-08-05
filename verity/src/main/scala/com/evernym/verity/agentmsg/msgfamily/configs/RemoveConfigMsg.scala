package com.evernym.verity.agentmsg.msgfamily.configs

import com.evernym.verity.agentmsg.msgfamily.{AgentMsgContext, LegacyMsgBase, TypeDetail}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_TYPE_CONFIGS_REMOVED
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.validate.ValidateHelper.checkRequired

case class RemoveConfigReqMsg_MFV_0_5(configs: Set[String]) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("configs", configs)
  }
}

case class RemoveConfigReqMsg(msgFamilyDetail: MsgFamilyDetail, configs: Set[String])

case class ConfigsRemovedRespMsg_MFV_0_5(`@type`: TypeDetail) extends LegacyMsgBase


object RemoveConfigMsgHelper {


  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): RemoveConfigReqMsg = {
    val msg = amw.headAgentMsg.convertTo[RemoveConfigReqMsg_MFV_0_5]
    RemoveConfigReqMsg(amw.headAgentMsgDetail,
      msg.configs)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): RemoveConfigReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case x => throw new RuntimeException("remove config req builder failed: " + x)
    }
  }

  def buildConfigRemovedResp_MFV_0_5: ConfigsRemovedRespMsg_MFV_0_5 = {
    ConfigsRemovedRespMsg_MFV_0_5(TypeDetail(MSG_TYPE_CONFIGS_REMOVED, MTV_1_0))
  }

  def buildRespMsg(implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_0_5 => List(buildConfigRemovedResp_MFV_0_5)
      case x => throw new RuntimeException("remove config resp builder failed: " + x)
    }
  }

}
