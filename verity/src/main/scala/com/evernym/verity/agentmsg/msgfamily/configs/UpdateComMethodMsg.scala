package com.evernym.verity.agentmsg.msgfamily.configs

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.MsgBase
import com.evernym.verity.protocol.engine.MsgFamily.{EVERNYM_QUALIFIER, typeStrFromMsgType}

case class ComMethodPackaging(pkgType: String, recipientKeys: Option[Set[String]]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("pkgType", pkgType)
    MsgPackFormat.fromString(pkgType) match {
      case MPF_INDY_PACK =>
        if (recipientKeys.getOrElse(Set.empty).isEmpty)
          throwMissingReqFieldException("recipientKeys")
      case _ => //
    }
  }
}

case class ComMethod(id: String, `type`: Int, value: String, packaging: Option[ComMethodPackaging]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("id", id)
    checkRequired("type", `type`)
    checkRequired("value", value)
    checkOptionalNotEmpty("packaging", packaging)
  }
}

case class UpdateComMethodReqMsg_MFV_0_5(comMethod: ComMethod) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("comMethod", comMethod)
  }
}

case class UpdateComMethodReqMsg_MFV_0_6(comMethod: ComMethod) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("comMethod", comMethod)
  }
}

case class UpdateComMethodReqMsg(comMethod: ComMethod)

case class ComMethodUpdatedRespMsg_MFV_0_5(`@type`: TypeDetail, id: String) extends MsgBase

case class ComMethodUpdatedRespMsg_MFV_0_6(`@type`: String, id: String) extends MsgBase


object UpdateComMethodMsgHelper {

  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): UpdateComMethodReqMsg = {
    val msg = amw.headAgentMsg.convertTo[UpdateComMethodReqMsg_MFV_0_5]
    UpdateComMethodReqMsg(msg.comMethod)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): UpdateComMethodReqMsg = {
    val msg = amw.headAgentMsg.convertTo[UpdateComMethodReqMsg_MFV_0_6]
    UpdateComMethodReqMsg(msg.comMethod)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): UpdateComMethodReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5          => buildReqMsgFrom_MFV_0_5
      case MFV_0_6| MFV_1_0 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("update com method req builder failed: " + x)
    }
  }

  def buildComMethodUpdatedResp_MFV_0_5(id: String): ComMethodUpdatedRespMsg_MFV_0_5 = {
    ComMethodUpdatedRespMsg_MFV_0_5(TypeDetail(MSG_TYPE_COM_METHOD_UPDATED, MTV_1_0), id)
  }

  def buildComMethodUpdatedResp_MFV_0_6(id: String): ComMethodUpdatedRespMsg_MFV_0_6 = {
    ComMethodUpdatedRespMsg_MFV_0_6(MSG_TYPE_DETAIL_COM_METHOD_UPDATED, id)
  }

  def buildComMethodUpdatedResp_MFV_1_0(id: String): ComMethodUpdatedRespMsg_MFV_0_6 = {
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_CONFIGS, MFV_1_0, MSG_TYPE_COM_METHOD_UPDATED)
    ComMethodUpdatedRespMsg_MFV_0_6(typeStr, id)
  }

  def buildRespMsg(id: String)(implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_0_5 => List(buildComMethodUpdatedResp_MFV_0_5(id))
      case MFV_0_6 => List(buildComMethodUpdatedResp_MFV_0_6(id))
      case MFV_1_0 => List(buildComMethodUpdatedResp_MFV_1_0(id))
      case x => throw new RuntimeException("update com method resp builder failed: " + x)
    }
  }
}

