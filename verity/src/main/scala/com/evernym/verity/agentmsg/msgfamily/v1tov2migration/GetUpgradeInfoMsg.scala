package com.evernym.verity.agentmsg.msgfamily.v1tov2migration


import com.evernym.verity.actor.agent.user.PairwiseUpgradeInfo
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{EVERNYM_QUALIFIER, typeStrFromMsgType}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.MsgBase


case class GetUpgradeInfo(pairwiseDIDs: List[String])


case class UpgradeInfoRespMsg_MFV_1_0(`@type`: String, data: Map[String, PairwiseUpgradeInfo]) extends MsgBase

object UpgradeInfoMsgHelper {

  def buildReqMsg(implicit amw: AgentMsgWrapper): GetUpgradeInfo = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_1_0  => buildReqMsgFrom_MFV_1_0
      case x        => throw new RuntimeException("update com method req builder failed: " + x)
    }
  }

  def buildRespMsg(data: Map[String, PairwiseUpgradeInfo])
                  (implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_1_0  => List(buildResp_MFV_1_0(data))
      case x        => throw new RuntimeException("update com method resp builder failed: " + x)
    }
  }

  private def buildReqMsgFrom_MFV_1_0(implicit amw: AgentMsgWrapper): GetUpgradeInfo = {
    amw.headAgentMsg.convertTo[GetUpgradeInfo]
  }

  def buildResp_MFV_1_0(data: Map[String, PairwiseUpgradeInfo]): UpgradeInfoRespMsg_MFV_1_0 = {
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_V1V2MIGRATION, MFV_1_0, MSG_TYPE_UPGRADE_INFO)
    UpgradeInfoRespMsg_MFV_1_0(typeStr, data)
  }
}


case class PairwiseUpgradeInfoRespMsg_MFV_1_0(`@type`: String,
                                              direction: String,
                                              theirAgencyDID: DidStr,
                                              theirAgencyVerKey: VerKeyStr,
                                              theirAgencyEndpoint: String) extends MsgBase

object UpgradePairwiseInfoMsgHelper {
  def buildRespMsg(data: PairwiseUpgradeInfo)
                  (implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_1_0  => List(buildPairwiseResp_MFV_1_0(data))
      case x        => throw new RuntimeException("update com method resp builder failed: " + x)
    }
  }

  def buildPairwiseResp_MFV_1_0(data: PairwiseUpgradeInfo): PairwiseUpgradeInfoRespMsg_MFV_1_0 = {
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_V1V2MIGRATION, MFV_1_0, MSG_TYPE_UPGRADE_INFO)
    PairwiseUpgradeInfoRespMsg_MFV_1_0(
      typeStr,
      data.direction,
      data.theirAgencyDID,
      data.theirAgencyVerKey,
      data.theirAgencyEndpoint
    )
  }
}
