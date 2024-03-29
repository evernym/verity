package com.evernym.verity.agentmsg.msgfamily.routing

import com.evernym.verity.util2.Exceptions.MissingReqFieldException
import com.evernym.verity.agentmsg.msgfamily.{LegacyMsgBase, TypeDetail}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.constants.Constants.MFV_1_0
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.validate.ValidateHelper.checkRequired
import com.evernym.verity.protocol.engine.MissingReqFieldProtocolEngineException
import com.evernym.verity.util.JsonObjectWrapper
import org.json.JSONObject


case class FwdReqMsg_MFV_0_5(`@type`: TypeDetail,
                             `@fwd`: String,
                             `@msg`: Array[Byte],
                             fwdMsgType: Option[String]) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@fwd", `@fwd`)
    checkRequired("@msg", `@msg`)
  }

}

case class FwdReqMsg_MFV_1_0(`@type`: String,
                             `@fwd`: String,
                             `@msg`: JSONObject,
                             fwdMsgType: Option[String],
                             fwdMsgSender: Option[String]) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@fwd", `@fwd`)
    checkRequired("@msg", `@msg`)
  }
}

//community forward message
case class FwdReqMsg_MFV_1_0_1(`@type`: String,
                               to: String,
                               msg: JSONObject,
                               fwdMsgType: Option[String],
                               fwdMsgSender: Option[String]) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("to", to)
    checkRequired("msg", msg)
  }
}

case class FwdReqMsg(msgFamilyDetail: MsgFamilyDetail,
                     `@fwd`: String,
                     `@msg`: Array[Byte],
                     fwdMsgType: Option[String],
                     fwdMsgSender: Option[String])

object FwdMsgHelper {

  // "MFV" stands for "Message Family Version." The community has stopped talking about
  // message families and now talks about protocols instead. It is protocols that actually
  // have versions, not message families so much. See
  // https://github.com/hyperledger/aries-rfcs/blob/master/concepts/0003-protocols/README.md#piuri
  // TODO: stop using "Message Family" as terminology in our codebase.
  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): FwdReqMsg = {
    val msg = amw.headAgentMsg.convertTo[FwdReqMsg_MFV_0_5]
    FwdReqMsg(amw.headAgentMsgDetail, msg.`@fwd`, msg.`@msg`, msg.fwdMsgType, None)
  }

  private def buildReqMsgFrom_MFV_1_0(implicit amw: AgentMsgWrapper): FwdReqMsg = {
    try {
      val fwdMsg = amw.headAgentMsg.convertTo[FwdReqMsg_MFV_1_0]
      FwdReqMsg(amw.headAgentMsgDetail, fwdMsg.`@fwd`, fwdMsg.`@msg`.toString().getBytes, fwdMsg.fwdMsgType, fwdMsg.fwdMsgSender)
    } catch {
      case _: MissingReqFieldException | _: MissingReqFieldProtocolEngineException =>
        val jsonObject = new JsonObjectWrapper(new JSONObject(amw.headAgentMsg.msg))
        val to = jsonObject.getString("to")
        val msg = jsonObject.getArrayBytes("msg")
        val fwdMsgType = jsonObject.getStringOption("fwdMsgType")
        val fwdMsgSender = jsonObject.getStringOption("fwdMsgSender")
        FwdReqMsg(amw.headAgentMsgDetail, to, msg, fwdMsgType, fwdMsgSender)
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
