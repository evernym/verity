package com.evernym.verity.protocol.engine

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

case class SignalEnvelope[+A](signalMsg: A, threadId: ThreadId, protoRef: ProtoRef, pinstId: PinstId, requestMsgId: Option[MsgId])
