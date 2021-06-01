package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0

import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.legacy.StateLegacy

trait Event
trait AnswerEvt extends Event {
  def response: String
  def received: String
}

trait State

object State extends StateLegacy {

  case class Uninitialized() extends State

  case class Initialized() extends State
  case class Error(errorCode:   Int,
                   comment:     String,
                   question:    Option[SegmentKey] = None,
                   response:    Option[SegmentKey] = None,
                   signature:   Option[SegmentKey] = None,
                   received:    Option[SegmentKey] = None,
                   validStatus: Option[AnswerValidity] = None) extends State

  // Questioner STATES:

  case class QuestionSent(id: SegmentKey) extends State

  case class AnswerReceived(id: SegmentKey, sigRequired: Boolean, validStatus: Option[AnswerValidity]) extends State

  // NOT a named state, but a dependent data structure
  case class AnswerValidity(answerValidity:    Boolean,
                            signatureValidity: Boolean,
                            timingValidity:    Boolean)

  // Responder STATES:

  case class QuestionReceived(id: SegmentKey) extends State

  case class AnswerSent(id:  SegmentKey) extends State
}
