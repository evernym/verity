package com.evernym.verity.agentmsg.msgfamily.routing

import com.evernym.verity.util2.Exceptions.MissingReqFieldException
import com.evernym.verity.agentmsg.msgfamily.TypeDetail
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.validate.ValidateHelper.checkRequired
import com.evernym.verity.protocol.engine.{MissingReqFieldProtocolEngineException, MsgBase}
import org.json.JSONObject


case class FwdReqMsg_MFV_0_5(`@type`: TypeDetail, `@fwd`: String, `@msg`: Array[Byte], fwdMsgType: Option[String]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@fwd", `@fwd`)
    checkRequired("@msg", `@msg`)
  }

}

case class FwdReqMsg_MFV_1_0(`@type`: String, `@fwd`: String, `@msg`: JSONObject, fwdMsgType: Option[String]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@fwd", `@fwd`)
    checkRequired("@msg", `@msg`)
  }
}

//community forward message
case class FwdReqMsg_MFV_1_0_1(`@type`: String, to: String, msg: JSONObject, fwdMsgType: Option[String]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("to", to)
    checkRequired("msg", msg)
  }
}

case class FwdReqMsg(msgFamilyDetail: MsgFamilyDetail, `@fwd`: String, `@msg`: Array[Byte], fwdMsgType: Option[String])

object FwdMsgHelper {

  // "MFV" stands for "Message Family Version." The community has stopped talking about
  // message families and now talks about protocols instead. It is protocols that actually
  // have versions, not message families so much. See
  // https://github.com/hyperledger/aries-rfcs/blob/master/concepts/0003-protocols/README.md#piuri
  // TODO: stop using "Message Family" as terminology in our codebase.
  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): FwdReqMsg = {
    val msg = amw.headAgentMsg.convertTo[FwdReqMsg_MFV_0_5]
    FwdReqMsg(amw.headAgentMsgDetail, msg.`@fwd`, msg.`@msg`, msg.fwdMsgType)
  }

  private def buildReqMsgFrom_MFV_1_0(implicit amw: AgentMsgWrapper): FwdReqMsg = {
    try {
      val fwdMsg = amw.headAgentMsg.convertTo[FwdReqMsg_MFV_1_0]
      FwdReqMsg(amw.headAgentMsgDetail, fwdMsg.`@fwd`, fwdMsg.`@msg`.toString().getBytes, fwdMsg.fwdMsgType)
    } catch {
      case _: MissingReqFieldException | _: MissingReqFieldProtocolEngineException =>
        val jsonObject = new JSONObject(amw.headAgentMsg.msg)
        val to = jsonObject.get("to").toString
        val msg = jsonObject.get("msg").toString.getBytes
        val fwdMsgType =
          if (jsonObject.has("fwdMsgType"))
            Option(jsonObject.getString("fwdMsgType"))
          else None
        FwdReqMsg(amw.headAgentMsgDetail, to, msg, fwdMsgType)
    }
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): FwdReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case MFV_1_0 => buildReqMsgFrom_MFV_1_0
      case x => throw new RuntimeException("fwd req builder failed: " + x)
    }
  }
}
