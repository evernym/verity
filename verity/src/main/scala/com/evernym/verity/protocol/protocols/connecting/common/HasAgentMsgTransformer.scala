package com.evernym.verity.protocol.protocols.connecting.common

import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer

trait HasAgentMsgTransformer {
  def agentMsgTransformer: AgentMsgTransformer
}
