package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper

trait MsgHelper[T] {

  def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): T

}
