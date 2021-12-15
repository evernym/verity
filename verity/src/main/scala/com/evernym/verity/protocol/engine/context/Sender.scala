package com.evernym.verity.protocol.engine.context

import com.evernym.verity.protocol.engine.{ParticipantId, ParticipantIndex}

/**
  * Protocol messages come from a sender, and this describes that sender
 *
  * @tparam R Role type
  */
trait SenderLike[R] {
  def id: Option[ParticipantId]
  def index: Option[ParticipantIndex]
  def role: Option[R]
  def id_! : ParticipantId
  def index_! : ParticipantIndex
  def role_! : R

}

case class Sender[R](id: Option[ParticipantId] = None, index: Option[ParticipantIndex] = None, role: Option[R] = None) extends SenderLike[R]{
  def id_! : ParticipantId = id.getOrElse(throw new RuntimeException("unknown sender id"))
  def index_! : ParticipantIndex = index.getOrElse(throw new RuntimeException("unknown sender index"))
  def role_! : R = role.getOrElse(throw new RuntimeException("unknown sender role"))
}

class NilSender[R] extends SenderLike[R] {
  def stop: Nothing = {
    throw new RuntimeException("Nil Sender should not be accessible")
  }
  def id: Option[ParticipantId] = stop
  def index: Option[ParticipantIndex] = stop
  def role: Option[R] = stop
  def id_! : ParticipantId = stop
  def index_! : ParticipantIndex = stop
  def role_! : R = stop

}