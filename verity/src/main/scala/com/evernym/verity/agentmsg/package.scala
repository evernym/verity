package com.evernym.verity

import com.evernym.verity.actor.agent.{ProtoMsgOrderDetail, TypeFormat}
import com.evernym.verity.agentmsg.msgcodec.{AgentJsonMsg, MsgCodec}
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.protocol.engine.{MsgId, ProtoDef, ThreadId}

package object agentmsg {

  val DefaultMsgCodec: MsgCodec = JacksonMsgCodec


  def buildAgentMsg(msg: Any, msgId: MsgId, threadId: ThreadId,
                    protoDef: ProtoDef, msgTypeFormat: TypeFormat,
                    protoMsgOrderDetail: Option[ProtoMsgOrderDetail]=None): AgentJsonMsg = {
    DefaultMsgCodec.toAgentMsg(msg, msgId, threadId, protoDef.msgFamily, msgTypeFormat, protoMsgOrderDetail)
  }
}

