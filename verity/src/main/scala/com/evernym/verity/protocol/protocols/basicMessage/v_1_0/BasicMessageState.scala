package com.evernym.verity.protocol.protocols.basicMessage.v_1_0

sealed trait State

object State {

  case class Uninitialized() extends State

  case class Initialized() extends State

  case class Error(errorCode:   Int,
                   comment:     String,
                   question:    Option[Msg.Message] = None,
                   response:    Option[String] = None,
                   signature:   Option[Sig] = None,
                   received:    Option[String] = None,
                  ) extends State

  // Receiver and Sender STATE:

  // Only one state
  case class Messaging(question:    Msg.Message,
                            response:    String,
                            signature:   Option[Sig],
                            received:    String
                            ) extends State
}

