package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.agentmsg.msgfamily.LegacyMsgBase
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.validate.ValidateHelper.{checkOptionalNotEmpty, checkRequired}


case class CreateConnectionReqMsg_MFV_0_6(`@type`: String, sourceId: String,
                                          phoneNo: Option[String]=None,
                                          includePublicDID: Option[Boolean]=None)
  extends LegacyMsgBase with Control  {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("sourceId", sourceId)
    checkOptionalNotEmpty("phoneNo", phoneNo)
    checkOptionalNotEmpty("includePublicDID", includePublicDID)
  }
}

object CreateConnectionMsgHelper {

  def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): CreateConnectionReqMsg_MFV_0_6 = {
    amw.headAgentMsg.convertTo[CreateConnectionReqMsg_MFV_0_6]
  }

}

