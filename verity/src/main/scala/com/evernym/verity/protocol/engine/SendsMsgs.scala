package com.evernym.verity.protocol.engine

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.ThreadContextDetail
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.protocol.engine.container.ProtocolContainer

import scala.concurrent.Future

/**
  * Services that sends messages
  *
  */
trait SendsMsgs {

  def prepare(env: Envelope1[Any]): ProtocolOutgoingMsg

  def send(pmsg: ProtocolOutgoingMsg): Unit

  //TODO how a message is sent should not be a concern of the caller
  def sendSMS(toPhoneNumber: String, msg: String): Future[String]
}

abstract class SendsMsgsForContainer[M](container: ProtocolContainer[_,_,M,_,_,_]) extends SendsMsgs {

  def prepare(env: Envelope1[Any]): ProtocolOutgoingMsg = {
    val msgIdReq = env.msgId getOrElse {
      throw new RuntimeException("msgId required while sending protocol outgoing message")
    }
    //TODO add checks here to make sure the from is correct, and that the recipient is actually an address that can be sent to
    ProtocolOutgoingMsg(env.msg, env.to, env.frm, msgIdReq, container.pinstId,
      container.definition, container.threadContextDetailReq)
  }
}

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
                               threadContextDetail: ThreadContextDetail) extends ActorMessage {
  def envelope: Envelope1[Any] = Envelope1(msg, to, from, Option(requestMsgId), Option(threadContextDetail.threadId))
}

