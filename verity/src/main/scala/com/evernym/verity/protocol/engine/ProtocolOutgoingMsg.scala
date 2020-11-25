package com.evernym.verity.protocol.engine

import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.ThreadContextDetail

/**
 *
 * @param msg - native message which needs to be sent to given recipient
 * @param to - to participant id (msg receiver's participant id) or service endpoint
 * @param from - from participant id (message sender's participant id)
 * @param requestMsgId- original/request msg id to which this is a response to
 * @param pinstId - protocol instance id
 * @param protoDef- protocol definition
 * @param threadContextDetail - thread context detail
 */
case class ProtocolOutgoingMsg(msg: Any,
                               to: ParticipantId,
                               from: ParticipantId,
                               requestMsgId: MsgId,
                               pinstId: PinstId,
                               protoDef: ProtoDef,
                               threadContextDetail: ThreadContextDetail) extends ActorMessageClass {
  def envelope: Envelope1[Any] = Envelope1(msg, to, from, Option(requestMsgId), Option(threadContextDetail.threadId))
}
