package com.evernym.verity.testkit.agentmsg

import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_TYPE_DETAIL_CREATE_AGENT
import com.evernym.verity.agentmsg.msgpacker.{AgentMessageWrapper, AgentMsgWrapper}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.{DidStr, VerKeyStr}

trait AgentMsgWrapperBuilder extends CommonSpecUtil {

  val testAppConfig: AppConfig = new TestAppConfig()
  override def appConfig: AppConfig = testAppConfig

  case class CreateAgentReqMsg_MFV_0_6(`@type`: String, fromDID: String, fromDIDVerKey: String)

  def buildCreateAgentMsgWrapper_MFV_0_6: AgentMsgWrapper = {
    val newDID = generateNewDid()
    buildCreateAgentMsgWrapper_MFV_0_6(newDID.did, newDID.verKey)
  }

  def buildCreateAgentMsgWrapper_MFV_0_6(fromDID: DidStr, fromDIDVerKey: VerKeyStr): AgentMsgWrapper = {
    val carm = CreateAgentReqMsg_MFV_0_6(MSG_TYPE_DETAIL_CREATE_AGENT, fromDID: DidStr, fromDIDVerKey: VerKeyStr)
    AgentMessageWrapper(DefaultMsgCodec.toJson(carm), MPF_INDY_PACK)
  }
}

object AgentMsgWrapperBuilder extends AgentMsgWrapperBuilder
