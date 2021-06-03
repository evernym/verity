package com.evernym.verity.protocol.protocols.basicMessage.v_1_0

import com.evernym.verity.protocol.TerminalState

trait Event

sealed trait State

object State {

  case class Uninitialized() extends State

  case class Initialized() extends State

  case class Error(errorCode:   Int,
                   comment:     String,
                   message:    Option[Msg.Message] = None,
                   content:    Option[String] = None,
                  ) extends State with TerminalState

  // Receiver and Sender STATE:

  // Only one state
  case class Messaging(message: Msg.Message, blobAddress: Option[String] = None)
    extends State with TerminalState
}

