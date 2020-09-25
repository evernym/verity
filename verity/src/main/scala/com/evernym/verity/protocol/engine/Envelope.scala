package com.evernym.verity.protocol.engine

trait Envelope[+M] {
  def msg: M
}

/** Protocol messages should be enclosed in an envelope that identifies sender
  * (from) and recipient (to).
  *
  * @param msg The message enclosed in the envelope.
  * @param frm The "from" or sender of the message.
  * @param to The "to" or recipient of the message.
  * @tparam M The message type to which the message conforms.
  */
case class Envelope1[+M](msg: M, to: ParticipantId, frm: ParticipantId, msgId: Option[MsgId], threadId: Option[ThreadId]) extends Envelope[M]

object Envelope1 {
  def apply[M](msg: M, to: ParticipantId, frm: ParticipantId, msgId: Option[MsgId]): Envelope1[M] = {
    apply(msg, to, frm, msgId, msgId)   //TODO: shall the last parameter value be msgId?
  }
}

case class Envelope2[+M](msg: M, frm: ParticipantId) extends Envelope[M]
