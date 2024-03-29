package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.did.DidStr
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.validate.ValidateHelper.checkOptionalNotEmpty


case class GetMsgsByConnsReqMsg_MFV_0_5(pairwiseDIDs: Option[List[DidStr]] = None,
                                        uids: Option[List[String]] = None,
                                        excludePayload: Option[String] = None,
                                        statusCodes: Option[List[String]] = None) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkOptionalNotEmpty("excludePayload", excludePayload)
  }
}

case class GetMsgsByConnsReqMsg_MFV_0_6(pairwiseDIDs: Option[List[DidStr]] = None,
                                        uids: Option[List[String]] = None,
                                        excludePayload: Option[String] = None,
                                        statusCodes: Option[List[String]] = None) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkOptionalNotEmpty("excludePayload", excludePayload)
  }
}

case class GetMsgsByConnsReqMsg(msgFamilyDetail: MsgFamilyDetail,
                                pairwiseDIDs: Option[List[DidStr]] = None,
                                excludePayload: Option[String] = None,
                                uids: Option[List[String]] = None,
                                statusCodes: Option[List[String]] = None)

case class MsgsByPairwiseConn(pairwiseDID: DidStr, msgs: List[MsgDetail]) extends LegacyMsgBase

case class GetMsgsByConnsRespMsg_MFV_0_5(`@type`: TypeDetail, msgsByConns: List[MsgsByPairwiseConn]) extends LegacyMsgBase

case class GetMsgsByConnsRespMsg_MFV_0_6(`@type`: String, msgsByConns: List[MsgsByPairwiseConn]) extends LegacyMsgBase


object GetMsgsByConnsMsgHelper extends MsgHelper[GetMsgsByConnsReqMsg] {

  def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): GetMsgsByConnsReqMsg = {
    val msg = amw.headAgentMsg.convertTo[GetMsgsByConnsReqMsg_MFV_0_5]
    GetMsgsByConnsReqMsg(amw.headAgentMsgDetail,
      msg.pairwiseDIDs, msg.excludePayload, msg.uids, msg.statusCodes)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): GetMsgsByConnsReqMsg = {
    val msg = amw.headAgentMsg.convertTo[GetMsgsByConnsReqMsg_MFV_0_6]
    GetMsgsByConnsReqMsg(amw.headAgentMsgDetail,
      msg.pairwiseDIDs, msg.excludePayload, msg.uids, msg.statusCodes)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): GetMsgsByConnsReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("get msgs by conns req builder failed: " + x)
    }
  }

  private def buildMsgsByConnsResp_MFV_0_5(msgsByConns: Map[String, List[MsgDetail]]): GetMsgsByConnsRespMsg_MFV_0_5 = {
    val pairwiseMsgs = msgsByConns.map { case (k, v) =>
      MsgsByPairwiseConn(k, v)
    }.toList
    GetMsgsByConnsRespMsg_MFV_0_5(TypeDetail(MSG_TYPE_MSGS_BY_CONNS, MTV_1_0), pairwiseMsgs)
  }

  private def buildMsgsByConnsResp_MFV_0_6(msgsByConns: Map[String, List[MsgDetail]]): GetMsgsByConnsRespMsg_MFV_0_6 = {
    val pairwiseMsgs = msgsByConns.map { case (k, v) =>
      MsgsByPairwiseConn(k, v)
    }.toList
    GetMsgsByConnsRespMsg_MFV_0_6(MSG_TYPE_DETAIL_MSGS_BY_CONNS, pairwiseMsgs)
  }

  def buildRespMsg(msgs: Map[String, scala.List[MsgDetail]])(implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_0_5 => List(buildMsgsByConnsResp_MFV_0_5(msgs))
      case MFV_0_6 => List(buildMsgsByConnsResp_MFV_0_6(msgs))
      case x => throw new RuntimeException("get msgs by conns response builder failed: " + x)
    }
  }

}


