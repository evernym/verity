package com.evernym.verity.protocol.engine

import com.evernym.verity.actor.agent.ThreadContextDetail
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.util.?=>

object Driver {
  type SignalHandler[A] = SignalEnvelope[A] ?=> Option[Control]
}

/**
  * A Driver is the controller of one or more instances of a protocol. It is
  * how a protocol can be integrated with any system. It is the glue between
  * a specific system and a hardened protocol.
  *
  * It receives Signal messages from a protocol and sends Control messages.
  *
  */
trait Driver {

  /**
    * Takes a SignalEnvelope and returns an optional Control message.
    *
    * A control message can always be sent later, in which case returning
    * a None is appropriate.
    *
    * @return an optional Control message
    */
  def signal[A]: SignalHandler[A]
}

/**
 *
 * @param signalMsg the signal message to be sent outside
 * @param protoRef protocol reference
 * @param pinstId protocol instance id
 * @param threadContextDetail thread context detail
 * @param requestMsgId request msg id
 * @tparam A
 */
case class SignalEnvelope[+A](signalMsg: A,
                              protoRef: ProtoRef,
                              pinstId: PinstId,
                              threadContextDetail: ThreadContextDetail,
                              requestMsgId: Option[MsgId]) {

  def threadId: ThreadId = threadContextDetail.threadId
}
