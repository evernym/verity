package com.evernym.verity.protocol.protocols

import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer

trait HasAgentMsgTransformer {
  def agentMsgTransformer: AgentMsgTransformer
}
