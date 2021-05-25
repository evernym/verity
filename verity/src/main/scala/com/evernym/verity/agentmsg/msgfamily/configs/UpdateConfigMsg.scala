package com.evernym.verity.agentmsg.msgfamily.configs

import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.MsgBase


case class UpdateConfigReqMsg_MFV_0_5(configs: Set[ConfigDetail]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("configs", configs)
    configs.foreach(_.validate())
  }
}

abstract class UpdateConfigCommand {
  def configs: Set[ConfigDetail]
}
case class UpdateConfigReqMsg(configs: Set[ConfigDetail]) extends UpdateConfigCommand
case class UpdateConfigs(configs: Set[ConfigDetail]) extends UpdateConfigCommand

case class ConfigsUpdatedRespMsg_MFV_0_5(`@type`: TypeDetail)

object UpdateConfigMsgHelper {

  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): UpdateConfigReqMsg = {
    val msg = amw.headAgentMsg.convertTo[UpdateConfigReqMsg_MFV_0_5]
    UpdateConfigReqMsg(msg.configs)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): UpdateConfigReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case x => throw new RuntimeException("update config req builder failed: " + x)
    }
  }

  def buildConfigUpdatedResp_MFV_0_5: ConfigsUpdatedRespMsg_MFV_0_5 = {
    ConfigsUpdatedRespMsg_MFV_0_5(TypeDetail(MSG_TYPE_CONFIGS_UPDATED, MTV_1_0))
  }

  def buildRespMsg(implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_0_5 => List(buildConfigUpdatedResp_MFV_0_5)
      case x => throw new RuntimeException("update config resp builder failed: " + x)
    }
  }
}

