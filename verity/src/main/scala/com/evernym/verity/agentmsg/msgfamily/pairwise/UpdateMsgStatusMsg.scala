package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.validate.ValidateHelper.checkRequired

case class UpdateMsgStatusReqMsg_MFV_0_5(statusCode: String, uids: List[String]) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("statusCode", statusCode)
    checkRequired("uids", uids)
  }
}

case class UpdateMsgStatusReqMsg_MFV_0_6(statusCode: String, uids: List[String]) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("statusCode", statusCode)
    checkRequired("uids", uids)
  }
}

case class UpdateMsgStatusReqMsg(statusCode: String, uids: List[String]) extends ActorMessage

case class MsgStatusUpdatedRespMsg_MFV_0_5(`@type`: TypeDetail, uids: List[String], statusCode: String) extends LegacyMsgBase

case class MsgStatusUpdatedRespMsg_MFV_0_6(`@type`: String, uids: List[String], statusCode: String) extends LegacyMsgBase


object UpdateMsgStatusMsgHelper {

  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): UpdateMsgStatusReqMsg = {
    val msg = amw.headAgentMsg.convertTo[UpdateMsgStatusReqMsg_MFV_0_5]
    UpdateMsgStatusReqMsg(msg.statusCode, msg.uids)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): UpdateMsgStatusReqMsg = {
    val msg = amw.headAgentMsg.convertTo[UpdateMsgStatusReqMsg_MFV_0_6]
    UpdateMsgStatusReqMsg(msg.statusCode, msg.uids)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): UpdateMsgStatusReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("update msg status req builder failed: " + x)
    }
  }

  private def buildMsgStatusUpdatedResp_MFV_0_5(uids: List[String], statusCode: String): MsgStatusUpdatedRespMsg_MFV_0_5 = {
    MsgStatusUpdatedRespMsg_MFV_0_5(
      TypeDetail(MSG_TYPE_MSG_STATUS_UPDATED, MTV_1_0),
      uids,
      statusCode)
  }

  private def buildMsgStatusUpdatedResp_MFV_0_6(uids: List[String], statusCode: String): MsgStatusUpdatedRespMsg_MFV_0_6 = {
    MsgStatusUpdatedRespMsg_MFV_0_6(MSG_TYPE_DETAIL_MSG_STATUS_UPDATED, uids, statusCode)
  }

  def buildRespMsg(uids: List[String], statusCode: String)(implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_0_5 => List(buildMsgStatusUpdatedResp_MFV_0_5(uids, statusCode))
      case MFV_0_6 => List(buildMsgStatusUpdatedResp_MFV_0_6(uids, statusCode))
      case x => throw new RuntimeException("update msg status response builder failed: " + x)
    }
  }
}
