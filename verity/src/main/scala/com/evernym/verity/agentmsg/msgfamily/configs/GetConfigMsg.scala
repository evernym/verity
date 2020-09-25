package com.evernym.verity.agentmsg.msgfamily.configs

import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_TYPE_CONFIGS
import com.evernym.verity.agentmsg.msgfamily.{AgentMsgContext, ConfigDetail, TypeDetail}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.MsgBase

case class GetConfigsReqMsg_MFV_0_5(configs: Set[String]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("configs", configs)
  }
}

case class GetConfigsReqMsg(msgFamilyDetail: MsgFamilyDetail, configs: Set[String])

case class GetConfigsRespMsg_MFV_0_5(`@type`: TypeDetail, configs: Set[ConfigDetail])

object GetConfigsMsgHelper {

  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): GetConfigsReqMsg = {
    val msg = amw.headAgentMsg.convertTo[GetConfigsReqMsg_MFV_0_5]
    GetConfigsReqMsg(amw.headAgentMsgDetail,
      msg.configs)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): GetConfigsReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case x => throw new RuntimeException("get config resq builder failed: " + x)
    }
  }

  def buildGetConfigsResp_MFV_0_5(configs: Set[ConfigDetail]): GetConfigsRespMsg_MFV_0_5 = {
    GetConfigsRespMsg_MFV_0_5(TypeDetail(MSG_TYPE_CONFIGS, MTV_1_0), configs)
  }

  def buildRespMsg(configs: Set[ConfigDetail])(implicit respMsgParam: AgentMsgContext): List[Any] = {
    respMsgParam.familyVersion match {
      case MFV_0_5 => List(buildGetConfigsResp_MFV_0_5(configs))
      case x => throw new RuntimeException("get config resp builder failed: " + x)
    }
  }

}

