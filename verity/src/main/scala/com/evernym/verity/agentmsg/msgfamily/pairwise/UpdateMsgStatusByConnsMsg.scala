package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.Exceptions.HandledErrorException
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{DID, MsgBase}
import com.evernym.verity.Status


case class UpdateMsgStatusByConnsReqMsg_MFV_0_5(`@type`: TypeDetail, statusCode: String,
                                                uidsByConns: List[PairwiseMsgUids]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("statusCode", statusCode)
    checkRequired("uidsByConns", uidsByConns)
  }
}

case class UpdateMsgStatusByConnsReqMsg_MFV_0_6(`@type`: String, statusCode: String,
                                                uidsByConns: List[PairwiseMsgUids]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("statusCode", statusCode)
    checkRequired("uidsByConns", uidsByConns)
  }
}

case class UpdateMsgStatusByConnsReqMsg(msgFamilyDetail: MsgFamilyDetail, statusCode: String,
                                        uidsByConns: List[PairwiseMsgUids])



case class MsgStatusUpdatedByConnsRespMsg_MFV_0_5(`@type`: TypeDetail, updatedUidsByConns: List[PairwiseMsgUids],
                                                  failed: Option[List[PairwiseError]]=None) extends MsgBase

case class MsgStatusUpdatedByConnsRespMsg_MFV_0_6(`@type`: String, updatedUidsByConns: List[PairwiseMsgUids],
                                                  failed: Option[List[PairwiseError]]=None) extends MsgBase


object UpdateMsgStatusByConnsMsgHelper {

  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): UpdateMsgStatusByConnsReqMsg = {
    val msg = amw.headAgentMsg.convertTo[UpdateMsgStatusByConnsReqMsg_MFV_0_5]
    UpdateMsgStatusByConnsReqMsg(amw.headAgentMsgDetail,
      msg.statusCode, msg.uidsByConns)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): UpdateMsgStatusByConnsReqMsg = {
    val msg = amw.headAgentMsg.convertTo[UpdateMsgStatusByConnsReqMsg_MFV_0_6]
    UpdateMsgStatusByConnsReqMsg(amw.headAgentMsgDetail,
      msg.statusCode, msg.uidsByConns)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): UpdateMsgStatusByConnsReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("update msg status by conns req builder failed: " + x)
    }
  }

  private def buildMsgStatusUpdateResponse(updatedMsgsByConns: Map[String, List[String]], failed: Map[String, HandledErrorException]):
    (List[PairwiseMsgUids], Option[scala.List[PairwiseError]]) = {
    val pairwiseMsgs = updatedMsgsByConns.map { case (k, v) =>
      PairwiseMsgUids(k, v)
    }.toList

    val failedMsgs = failed.map { case (k, he) =>
      PairwiseError(k, he.respCode, he.respMsg.getOrElse(Status.getStatusMsgFromCode(he.respCode)))
    }.toList

    val failedMsgsByConns = if (failedMsgs.isEmpty) None else Option(failedMsgs)
    (pairwiseMsgs, failedMsgsByConns)
  }

  private def buildMsgStatusUpdatedByConnsResp_MFV_0_5(updatedMsgsByConns: Map[String, List[String]],
                                                       failed: Map[String, HandledErrorException]):
  MsgStatusUpdatedByConnsRespMsg_MFV_0_5 = {
    val (pairwiseMsgs, failedMsgsByConns) = buildMsgStatusUpdateResponse(updatedMsgsByConns, failed)
    MsgStatusUpdatedByConnsRespMsg_MFV_0_5(
      TypeDetail(MSG_TYPE_MSG_STATUS_UPDATED_BY_CONNS, MTV_1_0),
      pairwiseMsgs,
      failedMsgsByConns)
  }

  private def buildMsgStatusUpdatedByConnsResp_MFV_0_6(updatedMsgsByConns: Map[String, List[String]],
                                               failed: Map[String, HandledErrorException]):
  MsgStatusUpdatedByConnsRespMsg_MFV_0_6 = {
    val (pairwiseMsgs, failedMsgsByConns) = buildMsgStatusUpdateResponse(updatedMsgsByConns, failed)

    MsgStatusUpdatedByConnsRespMsg_MFV_0_6(
      MSG_TYPE_DETAIL_MSG_STATUS_UPDATED_BY_CONNS,
      pairwiseMsgs,
      failedMsgsByConns)
  }


  def buildRespMsg(successful: Map[String, List[String]], failed:  Map[String, HandledErrorException])
                  (implicit respMsgParam: AgentMsgContext): List[Any] = {
    respMsgParam.familyVersion match {
      case MFV_0_5 => List(buildMsgStatusUpdatedByConnsResp_MFV_0_5(successful, failed))
      case MFV_0_6 => List(buildMsgStatusUpdatedByConnsResp_MFV_0_6(successful, failed))
      case x => throw new RuntimeException("update msg status by conns response builder failed: " + x)
    }
  }
}

case class PairwiseMsgUids(pairwiseDID: DID, uids: List[String]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("pairwiseDID", pairwiseDID)
    checkRequired("uids", uids)
  }
}

case class PairwiseError(pairwiseDID: DID, statusCode: String, statusMsg: String) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("pairwiseDID", pairwiseDID)
    checkRequired("statusCode", statusCode)
    checkRequired("statusMsg", statusMsg)
  }
}