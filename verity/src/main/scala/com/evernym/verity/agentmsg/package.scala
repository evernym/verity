package com.evernym.verity

import com.evernym.verity.actor.agent.{MsgOrders, TypeFormat}
import com.evernym.verity.agentmsg.msgcodec.MsgCodec
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.didcomm.v1.messages.{MsgId, MsgType}
import com.evernym.verity.protocol.engine.{ProtoDef, ThreadId}

package object agentmsg {

  val DefaultMsgCodec: MsgCodec = JacksonMsgCodec


  def buildAgentMsg(msg: Any, msgId: MsgId, threadId: ThreadId,
                    protoDef: ProtoDef, msgTypeFormat: TypeFormat,
                    msgOrders: Option[MsgOrders]=None): AgentJsonMsg = {
    DefaultMsgCodec.toAgentMsg(msg, msgId, threadId, protoDef.msgFamily, msgTypeFormat, msgOrders)
  }

  case class AgentJsonMsg(jsonStr: String, msgType: MsgType)
}

