package com.evernym.verity.agentmsg

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK, MPF_PLAIN}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_TYPE_MSGS_SENT, MSG_TYPE_MSG_CREATED, MSG_TYPE_MSG_DETAIL}
import com.evernym.verity.agentmsg.msgfamily.pairwise.{MsgCreatedRespMsg_MFV_0_5, MsgsSentRespMsg_MFV_0_5}
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.protocol.engine.{MsgBase, MsgId, VerKey}

package object msgfamily {

  case class TypeDetail(name: String, ver: String, fmt: Option[String]=None) extends MsgBase {
    override def validate(): Unit = {
      checkOptionalNotEmpty("fmt", fmt)
    }
  }

  case class LegacyTypedMsg(`@type`: TypeDetail) extends MsgBase {
    override def validate(): Unit = {
      checkRequired("@type", `@type`)
    }
  }

  case class ConfigDetail(name: String, value: String) extends MsgBase {
    override def validate(): Unit = {
      checkRequired("name", name)
      checkRequired("value", value)
    }
  }

  case class BundledMsg_MFV_0_5(bundled: List[Array[Byte]]) extends MsgBase {
    override def validate(): Unit = {
      checkRequired("bundled", bundled)
    }
  }

  case class AgentMsgContext(msgPackFormat: MsgPackFormat, familyVersion: String, senderVerKey: Option[VerKey]) {

    /**
     * for rest api (MPF_PLAIN), when messages are exchanged between agencies
     * we want to use INDY PACK by default
     * @return
     */
    def msgPackFormatToBeUsed: MsgPackFormat = msgPackFormat match {
      case MPF_PLAIN => MPF_INDY_PACK
      case x         => x
    }

    def wrapInBundledMsg: Boolean = msgPackFormat match {
      case MPF_MSG_PACK => true
      case _            => false
    }
  }

  def buildMsgCreatedTypeDetail(ver: String): TypeDetail = TypeDetail(MSG_TYPE_MSG_CREATED, ver)

  def buildMsgDetailTypeDetail(ver: String): TypeDetail = TypeDetail(MSG_TYPE_MSG_DETAIL, ver)

  def buildMsgCreatedResp_MFV_0_5(uid: MsgId): MsgCreatedRespMsg_MFV_0_5 = {
    MsgCreatedRespMsg_MFV_0_5(buildMsgCreatedTypeDetail(MTV_1_0), uid)
  }

  def buildMsgsSentResp_MFV_0_5(uids: List[MsgId]): MsgsSentRespMsg_MFV_0_5 = {
    MsgsSentRespMsg_MFV_0_5(TypeDetail(MSG_TYPE_MSGS_SENT, MTV_1_0), uids)
  }

}
