package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.validate.ValidateHelper.checkRequired


case class UpdateConnStatusReqMsg_MFV_0_5(`@type`: TypeDetail, statusCode: String) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("statusCodes", statusCode)
  }
}

case class UpdateConnStatusReqMsg_MFV_0_6(`@type`: String, statusCode: String) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("statusCodes", statusCode)
  }
}

case class UpdateConnStatusReqMsg(msgFamilyDetail: MsgFamilyDetail, statusCode: String)

case class ConnStatusUpdatedRespMsg_MFV_0_5(`@type`: TypeDetail, statusCode: String) extends LegacyMsgBase

case class ConnStatusUpdatedRespMsg_MFV_0_6(`@type`: String, statusCode: String) extends LegacyMsgBase


object UpdateConnStatusMsgHelper {

  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): UpdateConnStatusReqMsg = {
    val msg = amw.headAgentMsg.convertTo[UpdateConnStatusReqMsg_MFV_0_5]
    UpdateConnStatusReqMsg(amw.headAgentMsgDetail,
      msg.statusCode)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): UpdateConnStatusReqMsg = {
    val msg = amw.headAgentMsg.convertTo[UpdateConnStatusReqMsg_MFV_0_6]
    UpdateConnStatusReqMsg(amw.headAgentMsgDetail,
      msg.statusCode)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): UpdateConnStatusReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("update conn status req builder failed: " + x)
    }
  }

  private def buildConnStatusUpdatedResp_MFV_0_5(statusCode: String): ConnStatusUpdatedRespMsg_MFV_0_5 = {
    ConnStatusUpdatedRespMsg_MFV_0_5(TypeDetail(MSG_TYPE_CONN_STATUS_UPDATED, MTV_1_0), statusCode)
  }

  private def buildConnStatusUpdatedResp_MFV_0_6(statusCode: String): ConnStatusUpdatedRespMsg_MFV_0_6 = {
    ConnStatusUpdatedRespMsg_MFV_0_6(MSG_TYPE_DETAIL_CONN_STATUS_UPDATED, statusCode)
  }

  def buildRespMsg(statusCode: String)(implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_0_5 => List(buildConnStatusUpdatedResp_MFV_0_5(statusCode))
      case MFV_0_6 => List(buildConnStatusUpdatedResp_MFV_0_6(statusCode))
      case x => throw new RuntimeException("update conn status response builder failed: " + x)
    }
  }
}

