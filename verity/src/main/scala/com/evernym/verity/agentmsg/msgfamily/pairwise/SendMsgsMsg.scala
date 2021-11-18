package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.MsgBase
import com.evernym.verity.protocol.engine.validate.ValidateHelper.checkRequired


case class SendMsgsReqMsg_MFV_0_5(`@type`: TypeDetail, uids: List[String]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("uids", uids)
  }
}

case class SendMsgsReqMsg_MFV_0_6(`@type`: String, uids: List[String]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("uids", uids)
  }
}

case class SendMsgsReqMsg(msgFamilyDetail: MsgFamilyDetail, uids: List[String])

case class MsgsSentRespMsg_MFV_0_5(`@type`: TypeDetail, uids: List[String]) extends MsgBase

object SendMsgsMsgHelper {

  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): SendMsgsReqMsg = {
    val msg = amw.headAgentMsg.convertTo[SendMsgsReqMsg_MFV_0_5]
    SendMsgsReqMsg(amw.headAgentMsgDetail, msg.uids)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): SendMsgsReqMsg = {
    val msg = amw.headAgentMsg.convertTo[SendMsgsReqMsg_MFV_0_6]
    SendMsgsReqMsg(amw.headAgentMsgDetail, msg.uids)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): SendMsgsReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("send msgs req builder failed: " + x)
    }
  }

  def buildRespMsg(uids: List[String])(implicit amc: AgentMsgContext): List[Any] = {
    amc.familyVersion match {
      case MFV_0_5 => List(buildMsgsSentResp_MFV_0_5(uids))
      case MFV_0_6 => List.empty
      case x => throw new RuntimeException("send msgs response builder failed: " + x)
    }
  }
}
