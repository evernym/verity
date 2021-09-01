package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_TYPE_GET_MSGS, _}
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.protocol.HasMsgType
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{MsgBase, MsgFamily, MsgName}
import com.evernym.verity.protocol.protocols.connecting.v_0_5.{ConnectingMsgFamily => ConnectingMsgFamily_0_5}
import com.evernym.verity.protocol.protocols.connecting.v_0_6.{ConnectingMsgFamily => ConnectingMsgFamily_0_6}


trait GetMsgsBaseMsg extends MsgBase with HasMsgType with ActorMessage {
  def excludePayload: Option[String]
  def uids: Option[List[String]]
  def statusCodes: Option[List[String]]

  def prepareGenericGetMsgs: GetMsgsReqMsg = GetMsgsReqMsg(excludePayload, uids, statusCodes)
}

case class GetMsgsReqMsg_MFV_0_5(excludePayload: Option[String],
                                 uids: Option[List[String]] = None,
                                 statusCodes: Option[List[String]] = None) extends GetMsgsBaseMsg {

  def msgName: MsgName = MSG_TYPE_GET_MSGS
  def msgFamily: MsgFamily = ConnectingMsgFamily_0_5

  override def validate(): Unit = {
    checkOptionalNotEmpty("excludePayload", excludePayload)
  }

}

case class GetMsgsReqMsg_MFV_0_6(excludePayload: Option[String],
                                 uids: Option[List[String]] = None,
                                 statusCodes: Option[List[String]] = None) extends GetMsgsBaseMsg {

  def msgName: MsgName = MSG_TYPE_GET_MSGS
  def msgFamily: MsgFamily = ConnectingMsgFamily_0_6

  override def validate(): Unit = {
    checkOptionalNotEmpty("excludePayload", excludePayload)
  }
}

case class GetMsgsReqMsg(excludePayload: Option[String],
                         uids: Option[List[String]] = None,
                         statusCodes: Option[List[String]] = None) extends MsgBase with ActorMessage

case class GetMsgsRespMsg_MFV_0_5(`@type`: TypeDetail, msgs: List[MsgDetail]) extends MsgBase

case class GetMsgsRespMsg_MFV_0_6(`@type`: String, msgs: List[MsgDetail]) extends MsgBase


object GetMsgsMsgHelper {

  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): GetMsgsReqMsg = {
    val gmr = amw.headAgentMsg.convertTo[GetMsgsReqMsg_MFV_0_5]
    GetMsgsReqMsg(gmr.excludePayload, gmr.uids, gmr.statusCodes)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): GetMsgsReqMsg = {
    val gmr = amw.headAgentMsg.convertTo[GetMsgsReqMsg_MFV_0_6]
    GetMsgsReqMsg(gmr.excludePayload, gmr.uids, gmr.statusCodes)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): GetMsgsReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("get msgs req builder failed: " + x)
    }
  }

  private def buildMsgsResp_MFV_0_5(msgs: List[MsgDetail]): GetMsgsRespMsg_MFV_0_5 = {
    GetMsgsRespMsg_MFV_0_5(TypeDetail(MSG_TYPE_MSGS, MTV_1_0), msgs)
  }

  private def buildMsgsResp_MFV_0_6(msgs: List[MsgDetail]): GetMsgsRespMsg_MFV_0_6 = {
    GetMsgsRespMsg_MFV_0_6(MSG_TYPE_DETAIL_MSGS, msgs)
  }

  def buildRespMsg(msgs: List[MsgDetail])(implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_0_5 => List(buildMsgsResp_MFV_0_5(msgs))
      case MFV_0_6 => List(buildMsgsResp_MFV_0_6(msgs))
      case x => throw new RuntimeException("get msgs response builder failed: " + x)
    }
  }
}

