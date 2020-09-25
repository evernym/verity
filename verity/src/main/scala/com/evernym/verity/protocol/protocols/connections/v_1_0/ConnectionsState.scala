package com.evernym.verity.protocol.protocols.connections.v_1_0

trait Event

sealed trait State

object State {

  case class Uninitialized() extends State

  case class Initialized() extends State

  case class Invited(inv: Msg.Invitation) extends State

  case class Accepted(rel: Msg.ProvisionalRelationship, label: String) extends State

  case class RequestSent(rel: Msg.ProvisionalRelationship) extends State

  case class RequestReceived(rel: Msg.Relationship) extends State

  case class ResponseSent(rel: Msg.Relationship) extends State

  case class ResponseReceived(rel: Msg.Relationship) extends State

  case class Completed(rel: Msg.Relationship) extends State
}
