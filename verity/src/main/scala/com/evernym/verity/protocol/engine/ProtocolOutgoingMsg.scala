package com.evernym.verity.protocol.engine

import com.evernym.verity.actor.ActorMessageClass

/**
  * Used by actor protocol container to send outgoing messages
  * by using protocol context's 'send' method
  *
  * @param msg - native message which needs to be sent to given recipient
  * @param to - to participant id (msg receiver's participant id) or service endpoint
  * @param from - from participant id (message sender's participant id)
  * @param requestMsgId - original msg id to which this is a response to
  * @param pinstId - protocol instance id which send this message
  * @param protoDef - protocol def
  */
case class ProtocolOutgoingMsg(msg: Any,
                                   to: ParticipantId,
                                   from: ParticipantId,
                                   requestMsgId: MsgId,
                                   threadId: ThreadId,
                                   pinstId: PinstId,
                                   protoDef: ProtoDef) extends ActorMessageClass {
  def envelope = Envelope1(msg, to, from, Option(requestMsgId), Option(threadId))
}
