package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{DID, MsgBase}
import com.evernym.verity.protocol.protocols.MsgDetail


case class GetMsgsByConnsReqMsg_MFV_0_5(pairwiseDIDs: Option[List[DID]] = None,
                                        excludePayload: Option[String] = None,
                                        uids: Option[List[String]] = None,
                                        statusCodes: Option[List[String]] = None) extends MsgBase {
  override def validate(): Unit = {
    checkOptionalNotEmpty("excludePayload", excludePayload)
  }
}

case class GetMsgsByConnsReqMsg_MFV_0_6(pairwiseDIDs: Option[List[DID]] = None,
                                        excludePayload: Option[String] = None,
                                        uids: Option[List[String]] = None,
                                        statusCodes: Option[List[String]] = None) extends MsgBase {
  override def validate(): Unit = {
    checkOptionalNotEmpty("excludePayload", excludePayload)
  }
}

case class GetMsgsByConnsReqMsg(msgFamilyDetail: MsgFamilyDetail,
                                pairwiseDIDs: Option[List[DID]] = None,
                                excludePayload: Option[String] = None,
                                uids: Option[List[String]] = None,
                                statusCodes: Option[List[String]] = None)

case class MsgsByPairwiseConn(pairwiseDID: DID, msgs: List[MsgDetail]) extends MsgBase

case class GetMsgsByConnsRespMsg_MFV_0_5(`@type`: TypeDetail, msgsByConns: List[MsgsByPairwiseConn]) extends MsgBase

case class GetMsgsByConnsRespMsg_MFV_0_6(`@type`: String, msgsByConns: List[MsgsByPairwiseConn]) extends MsgBase


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

  def buildRespMsg(msgs: Map[String, scala.List[MsgDetail]])(implicit respMsgParam: AgentMsgContext): List[Any] = {
    respMsgParam.familyVersion match {
      case MFV_0_5 => List(buildMsgsByConnsResp_MFV_0_5(msgs))
      case MFV_0_6 => List(buildMsgsByConnsResp_MFV_0_6(msgs))
      case x => throw new RuntimeException("get msgs by conns response builder failed: " + x)
    }
  }

}


