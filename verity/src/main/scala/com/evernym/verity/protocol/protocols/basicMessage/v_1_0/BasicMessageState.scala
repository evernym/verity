package com.evernym.verity.protocol.protocols.basicMessage.v_1_0

sealed trait State

object State {

  case class Uninitialized() extends State

  case class Initialized() extends State

  case class Error(errorCode:   Int,
                   comment:     String,
                   message:    Option[Msg.Message] = None,
                   content:    Option[String] = None,
                  ) extends State

  // Receiver and Sender STATE:

  // Only one state
  case class Messaging(message: Msg.Message) extends State
}

