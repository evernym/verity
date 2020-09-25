package com.evernym.verity.protocol.protocols.trustping.v_1_0

trait Event

sealed trait State

object State {

  case class Uninitialized() extends State

  case class Initialized() extends State

  case class SentPing(msg: Msg.Ping) extends State

  case class ReceivedResponse(msg: Msg.Response) extends State

  case class ReceivedPing(msg: Msg.Ping) extends State

  case class SentResponded(msg: Option[Msg.Response]) extends State
}

